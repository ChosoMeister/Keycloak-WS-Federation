package io.github.chosomeister.keycloak.protocol.wsfed;

import io.github.chosomeister.keycloak.common.wsfed.parsers.WSTrustParser;
import io.github.chosomeister.keycloak.protocol.wsfed.builders.RequestSecurityTokenResponseBuilder;
import io.github.chosomeister.keycloak.protocol.wsfed.builders.WSFedSAML2AssertionTypeBuilder;
import io.github.chosomeister.keycloak.protocol.wsfed.builders.WsFedSAML11AssertionTypeBuilder;

import org.jboss.logging.Logger;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.Time;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.services.managers.ClientSessionCode;
import org.keycloak.services.util.DefaultClientSessionContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityToken;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.keycloak.services.resources.RealmsResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The active WS-Trust profile: a relying party sends username and password in a SOAP request and
 * receives the same signed token the browser flow issues.
 *
 * <p>Clients that cannot use a browser need this, the Dynamics 365 .NET SDK among them. It is off
 * unless a realm turns it on, because unlike the passive endpoint it accepts credentials directly
 * and so is worth exposing only where something needs it.
 *
 * <p>Everything about the token itself comes from the relying party's own client: which claims it
 * carries, whether it is SAML 1.1 or 2.0, how the signature names the key, and whether it is
 * encrypted. An administrator configures a client once and both endpoints honour it.
 */
public class WSTrustActiveService {

    private static final Logger logger = Logger.getLogger(WSTrustActiveService.class);

    /** Realm attribute turning the active endpoint on. Absent means off. */
    public static final String ENABLED_ATTRIBUTE = "wsfed.ws-trust.enabled";

    /**
     * Realm attribute that lets a user who has a second factor configured sign in here with a
     * password alone. The active profile carries no second factor, so by default such a user is
     * refused: accepting them would make this endpoint a way around the second factor that the
     * browser flow enforces. Turn it on only where people run tools that use this endpoint with
     * their own accounts, and accept that for them this path is protected by the password only.
     */
    public static final String ALLOW_PASSWORD_ONLY_ATTRIBUTE = "wsfed.ws-trust.allow-password-only";

    private static final String AUTH_METHOD = "wsfed-ws-trust";
    private static final String GENERIC_AUTH_FAILURE = "Authentication failed.";

    private final KeycloakSession session;
    private final RealmModel realm;
    private final EventBuilder event;

    public WSTrustActiveService(KeycloakSession session, RealmModel realm, EventBuilder event) {
        this.session = session;
        this.realm = realm;
        this.event = event;
    }

    // Read from the Keycloak context rather than injected: this is reached through a sub-resource
    // locator, and the JAX-RS implementation does not inject into an object constructed by hand.

    private UriInfo uriInfo() {
        return session.getContext().getUri();
    }

    private ClientConnection connection() {
        return session.getContext().getConnection();
    }

    /**
     * @param realm the realm to check
     * @return whether the realm has opted into the active endpoint
     */
    public static boolean isEnabled(RealmModel realm) {
        return Boolean.parseBoolean(realm.getAttribute(ENABLED_ATTRIBUTE));
    }

    /**
     * Serves the WSDL describing the active endpoint, so a client can discover it the way it
     * discovers AD FS rather than being pointed at the address by hand.
     *
     * <p>Routed from {@link WSFedService} so that it sits beside the protocol endpoint rather
     * than beneath the active one, which is where a client looks for it.
     *
     * @return the metadata document, or 404 where the realm has not enabled the profile
     */
    public Response metadataExchange() {
        if (!isEnabled(realm)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            return Response.ok(metadataDocument(), MediaType.APPLICATION_XML_TYPE).build();
        } catch (IOException e) {
            logger.error("Could not read the WS-Trust metadata template", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String metadataDocument() throws IOException {
        try (InputStream template = getClass().getClassLoader()
                .getResourceAsStream("wsfed-ws-trust-mex-template.xml")) {
            if (template == null) {
                throw new IOException("wsfed-ws-trust-mex-template.xml is missing from the provider");
            }
            return new String(template.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("${wstrust.usernamemixed}", endpointAddress());
        }
    }

    /**
     * @return the absolute address of the active endpoint, as the caller reached this realm
     */
    String endpointAddress() {
        return RealmsResource.protocolUrl(uriInfo())
                .path(WSFedService.class, "activeRequestor")
                .build(realm.getName(), WSFedLoginProtocol.LOGIN_PROTOCOL)
                .toString();
    }

    @POST
    @Consumes({"application/soap+xml", MediaType.TEXT_XML, MediaType.APPLICATION_XML})
    public Response issue(String body) {
        event.event(EventType.LOGIN);
        event.detail(Details.AUTH_METHOD, AUTH_METHOD);

        if (!isEnabled(realm)) {
            // Indistinguishable from the endpoint not existing, which is what it is for a realm
            // that has not asked for it.
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            return issueToken(body);
        } catch (WSTrustFault fault) {
            event.error(fault.error);
            logger.debugf("Refusing WS-Trust request: %s", fault.getMessage());
            return soapFault(fault.sender, fault.reason);
        } catch (Exception e) {
            event.error(Errors.INVALID_REQUEST);
            logger.error("Failed to issue a WS-Trust token", e);
            return soapFault(false, "The request could not be processed.");
        }
    }

    private Response issueToken(String body) throws Exception {
        if (!"https".equals(uriInfo().getBaseUri().getScheme())
                && realm.getSslRequired().isRequired(connection())) {
            throw new WSTrustFault(true, "HTTPS is required.", Errors.SSL_REQUIRED);
        }

        if (body == null || body.isEmpty()) {
            throw new WSTrustFault(true, "The request body is empty.", Errors.INVALID_REQUEST);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > WSTrustSoap.MAX_REQUEST_BYTES) {
            throw new WSTrustFault(true, "The request is too large.", Errors.INVALID_REQUEST);
        }

        Document envelope = WSTrustSoap.parseEnvelope(body);

        if (!WSTrustSoap.isSoap12Envelope(envelope)) {
            throw new WSTrustFault(true, "Only SOAP 1.2 envelopes are supported.", Errors.INVALID_REQUEST);
        }

        Element rstElement = WSTrustSoap.requestSecurityToken(envelope);
        if (rstElement == null) {
            throw new WSTrustFault(true, "The request carries no RequestSecurityToken.", Errors.INVALID_REQUEST);
        }

        RequestSecurityToken request = parseRequest(rstElement);
        ClientModel client = resolveClient(request);
        UserModel user = authenticate(envelope, client);

        return issueFor(client, user, messageId(envelope));
    }

    private RequestSecurityToken parseRequest(Element rstElement) throws Exception {
        String xml = WSTrustSoap.toXml(rstElement);
        try (ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            Object parsed = new WSTrustParser().parse(in);
            if (!(parsed instanceof RequestSecurityToken)) {
                throw new WSTrustFault(true, "Unsupported request.", Errors.INVALID_REQUEST);
            }
            return (RequestSecurityToken) parsed;
        }
    }

    /**
     * The relying party names itself through AppliesTo, exactly as wtrealm does in the browser
     * flow, and the same checks apply to the client that names it.
     */
    private ClientModel resolveClient(RequestSecurityToken request) {
        String appliesTo = null;
        if (request.getAppliesTo() != null && request.getAppliesTo().getAny() != null) {
            for (Object any : request.getAppliesTo().getAny()) {
                if (any instanceof org.picketlink.identity.federation.ws.addressing.EndpointReferenceType ref
                        && ref.getAddress() != null) {
                    appliesTo = ref.getAddress().getValue();
                    break;
                }
            }
        }

        if (appliesTo == null || appliesTo.isEmpty()) {
            throw new WSTrustFault(true, "The request does not say which relying party it is for.",
                    Errors.INVALID_REQUEST);
        }

        ClientModel client = realm.getClientByClientId(appliesTo);
        if (client == null || !client.isEnabled() || client.isBearerOnly()
                || !WSFedLoginProtocol.LOGIN_PROTOCOL.equals(client.getProtocol())) {
            // One message for every reason, so the caller cannot probe which clients exist.
            throw new WSTrustFault(true, "Unknown relying party.", Errors.CLIENT_NOT_FOUND);
        }

        // Keycloak's own switch for whether a client may be given tokens in exchange for a password.
        // The realm attribute decides whether the endpoint exists; this decides, per relying party,
        // whether it may be used, exactly as it does for the OpenID Connect password grant.
        if (!client.isDirectAccessGrantsEnabled()) {
            event.client(client);
            throw new WSTrustFault(true, "This relying party does not accept password sign-in.", Errors.NOT_ALLOWED);
        }

        event.client(client);
        session.getContext().setClient(client);
        return client;
    }

    private UserModel authenticate(Document envelope, ClientModel client) {
        Element usernameToken = WSTrustSoap.usernameToken(envelope);
        if (usernameToken == null) {
            throw new WSTrustFault(true, "The request carries no UsernameToken.", Errors.INVALID_REQUEST);
        }

        String username = WSTrustSoap.text(WSTrustSoap.firstChild(usernameToken, WSTrustSoap.WSSE_NS, "Username"));
        Element passwordElement = WSTrustSoap.firstChild(usernameToken, WSTrustSoap.WSSE_NS, "Password");
        String password = WSTrustSoap.text(passwordElement);

        if (username == null || password == null) {
            throw new WSTrustFault(true, GENERIC_AUTH_FAILURE, Errors.INVALID_USER_CREDENTIALS);
        }

        // A digested password cannot be checked against Keycloak's stored hash, so it is refused
        // rather than silently treated as if it were the password itself.
        String passwordType = passwordElement.getAttribute("Type");
        if (!passwordType.isEmpty() && !WSTrustSoap.PASSWORD_TEXT_TYPE.equals(passwordType)) {
            throw new WSTrustFault(true, "Only PasswordText is supported.", Errors.INVALID_USER_CREDENTIALS);
        }

        event.detail(Details.USERNAME, username);

        UserModel user = session.users().getUserByUsername(realm, username);
        if (user == null) {
            user = session.users().getUserByEmail(realm, username);
        }

        if (user == null) {
            // Spend the time a password check would, so the response time does not reveal which
            // usernames exist. Keycloak does the same in its own login forms.
            dummyHash();
            throw new WSTrustFault(true, GENERIC_AUTH_FAILURE, Errors.USER_NOT_FOUND);
        }

        if (!user.isEnabled()) {
            event.user(user);
            throw new WSTrustFault(true, GENERIC_AUTH_FAILURE, Errors.USER_DISABLED);
        }

        BruteForceProtector protector = session.getProvider(BruteForceProtector.class);
        if (realm.isBruteForceProtected() && protector != null
                && protector.isTemporarilyDisabled(session, realm, user)) {
            event.user(user);
            throw new WSTrustFault(true, GENERIC_AUTH_FAILURE, Errors.USER_TEMPORARILY_DISABLED);
        }

        boolean valid = user.credentialManager().isValid(UserCredentialModel.password(password));

        if (realm.isBruteForceProtected() && protector != null) {
            if (valid) {
                protector.successfulLogin(realm, user, connection(), uriInfo(), java.util.Set.of());
            } else {
                protector.failedLogin(realm, user, connection(), uriInfo(), java.util.Set.of());
            }
        }

        if (!valid) {
            event.user(user);
            throw new WSTrustFault(true, GENERIC_AUTH_FAILURE, Errors.INVALID_USER_CREDENTIALS);
        }

        event.user(user);

        String refusal = refusalAfterPassword(realm, user);
        if (refusal != null) {
            throw new WSTrustFault(true, refusal, Errors.NOT_ALLOWED);
        }

        return user;
    }

    /**
     * The checks Keycloak's own password grant makes once the password is known to be right. They
     * run only then, so that a caller without the password learns nothing about the account.
     *
     * @return the reason to refuse the user, or null when the token may be issued
     */
    static String refusalAfterPassword(RealmModel realm, UserModel user) {
        // A pending required action, such as a password that must be changed, means the account
        // is not ready for use. There is no page here on which to complete it.
        if (user.getRequiredActionsStream().findAny().isPresent()) {
            return "Account is not fully set up.";
        }

        // The browser flow asks such a user for their second factor; this profile cannot.
        if (user.credentialManager().isConfiguredFor(OTPCredentialModel.TYPE)
                && !Boolean.parseBoolean(realm.getAttribute(ALLOW_PASSWORD_ONLY_ATTRIBUTE))) {
            return "A second factor is required for this account, and this endpoint cannot accept one.";
        }

        return null;
    }

    private void dummyHash() {
        PasswordPolicy policy = realm.getPasswordPolicy();
        PasswordHashProvider provider = policy != null && policy.getHashAlgorithm() != null
                ? session.getProvider(PasswordHashProvider.class, policy.getHashAlgorithm())
                : session.getProvider(PasswordHashProvider.class);
        if (provider != null) {
            provider.encodedCredential("SlightlyLongerDummyPassword", policy != null ? policy.getHashIterations() : -1);
        }
    }

    /**
     * Builds and signs the token. A transient session is created so the client's protocol mappers
     * run exactly as they do in the browser flow; it is never persisted, because there is no
     * browser session here to keep alive.
     */
    private Response issueFor(ClientModel client, UserModel user, String messageId) throws Exception {
        UserSessionModel userSession = session.sessions().createUserSession(
                UUID.randomUUID().toString(), realm, user, user.getUsername(),
                connection().getRemoteAddr(), AUTH_METHOD, false, null, null,
                UserSessionModel.SessionPersistenceState.TRANSIENT);
        userSession.setNote(AuthenticationManager.AUTH_TIME, String.valueOf(Time.currentTime()));

        AuthenticatedClientSessionModel clientSession =
                session.sessions().createClientSession(realm, client, userSession);
        clientSession.setRedirectUri(client.getBaseUrl());

        ClientSessionContext clientSessionCtx =
                DefaultClientSessionContext.fromClientSessionScopeParameter(clientSession, session);

        RequestSecurityTokenResponseBuilder builder = new RequestSecurityTokenResponseBuilder();
        builder.setRealm(client.getClientId())
                .setRequestIssuer(client.getClientId())
                .setTokenExpiration(io.github.chosomeister.keycloak.protocol.wsfed.builders.WsFedSAMLAssertionTypeAbstractBuilder.tokenLifespan(realm, client, userSession));

        WSFedLoginProtocol.configureTokenSecurity(session, realm, client, builder);

        ClientSessionCode<AuthenticatedClientSessionModel> accessCode =
                new ClientSessionCode<>(session, realm, clientSession);

        if (WSFedLoginProtocol.getSamlAssertionTokenFormat(client)
                == WsFedSAMLAssertionTokenFormat.SAML11_ASSERTION_TOKEN_FORMAT) {
            WSFedLoginProtocol.warnIfKeyNameSettingCannotApply(client);
            builder.setSaml11Token(new WsFedSAML11AssertionTypeBuilder()
                    .setRealm(realm).setUriInfo(uriInfo()).setAccessCode(accessCode)
                    .setClientSession(clientSession).setUserSession(userSession)
                    .setSession(session).build());
        } else {
            builder.setSamlToken(new WSFedSAML2AssertionTypeBuilder()
                    .setRealm(realm).setUriInfo(uriInfo()).setAccessCode(accessCode)
                    .setClientSession(clientSession).setUserSession(userSession)
                    .setSession(session).build());
        }

        String response = WSTrustSoap.envelope(
                RequestSecurityTokenResponseBuilder.getStringValue(builder.build()), messageId);

        event.session(userSession);
        event.success();

        return Response.ok(response, "application/soap+xml;charset=UTF-8").build();
    }

    private String messageId(Document envelope) {
        return WSTrustSoap.text(WSTrustSoap.firstChild(envelope, WSTrustSoap.ADDRESSING_NS, "MessageID"));
    }

    private Response soapFault(boolean sender, String reason) {
        // SOAP 1.2 carries faults with a 500, whoever is at fault.
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(WSTrustSoap.fault(sender, reason))
                .type("application/soap+xml;charset=UTF-8")
                .build();
    }

    /** Signals a refusal that should reach the caller as a SOAP fault. */
    private static final class WSTrustFault extends RuntimeException {
        private final boolean sender;
        private final String reason;
        private final String error;

        private WSTrustFault(boolean sender, String reason, String error) {
            super(reason);
            this.sender = sender;
            this.reason = reason;
            this.error = error;
        }
    }
}
