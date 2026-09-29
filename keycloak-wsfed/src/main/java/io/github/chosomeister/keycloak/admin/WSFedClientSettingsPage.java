package io.github.chosomeister.keycloak.admin;

import io.github.chosomeister.keycloak.protocol.wsfed.WSFedLoginProtocol;
import io.github.chosomeister.keycloak.protocol.wsfed.builders.WsFedSAMLAssertionTypeAbstractBuilder;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.protocol.saml.SamlConfigAttributes;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.ui.extend.UiPageProvider;
import org.keycloak.services.ui.extend.UiPageProviderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.chosomeister.keycloak.admin.ConsoleSetting.attribute;

/**
 * A "WS-Federation clients" page listing every WS-Federation client with the settings the stock
 * console has no field for: token format, key name, lifetime, encryption, sign-out cleanup and
 * password sign-in for the active endpoint. The client attributes remain what the extension reads;
 * this page writes them and is refreshed whenever a client changes.
 */
public class WSFedClientSettingsPage implements UiPageProvider, UiPageProviderFactory<ComponentModel> {

    public static final String ID = "WS-Federation clients";

    /** The client the item is for, chosen by client id. */
    static final String CLIENT_ID = "clientId";

    static final List<ConsoleSetting<ClientModel>> SETTINGS = List.of(
            attribute("tokenFormat", "Token format",
                    "SAML 2.0 or SAML 1.1. Dynamics 365 accepts either. Re-run the AD claims script after"
                            + " changing it, because the two formats name attributes differently.",
                    ProviderConfigProperty.LIST_TYPE, List.of("SAML 2.0", "SAML 1.1"), "SAML 2.0",
                    c -> c.getAttribute(WSFedLoginProtocol.WSFED_SAML_ASSERTION_TOKEN_FORMAT),
                    (c, v) -> c.setAttribute(WSFedLoginProtocol.WSFED_SAML_ASSERTION_TOKEN_FORMAT, v)),
            attribute("keyName", "Key name in signature",
                    "What the signature's KeyInfo names the key with. WIF and .NET relying parties such as"
                            + " Dynamics 365 need NONE; with a KeyName they fail with ID4037.",
                    ProviderConfigProperty.LIST_TYPE, List.of("KEY_ID", "NONE", "CERT_SUBJECT"), "KEY_ID",
                    c -> c.getAttribute(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER),
                    (c, v) -> c.setAttribute(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, v)),
            flag("lifespanFromSession", "Token lifetime follows the Keycloak session",
                    "The token lasts as long as the user's Keycloak session (Realm settings, Sessions: SSO"
                            + " Session Idle and Max). Without this or a fixed lifetime the token lasts 60"
                            + " seconds and the relying party signs the user out after about a minute.",
                    WsFedSAMLAssertionTypeAbstractBuilder.LIFESPAN_FROM_SESSION_ATTRIBUTE),
            attribute("assertionLifespan", "Fixed token lifetime (seconds)",
                    "A fixed lifetime in seconds for the token's validity windows. Takes precedence over"
                            + " following the session. Leave empty to not set one.",
                    ProviderConfigProperty.STRING_TYPE, null, null,
                    c -> c.getAttribute(SamlConfigAttributes.SAML_ASSERTION_LIFESPAN),
                    (c, v) -> c.setAttribute(SamlConfigAttributes.SAML_ASSERTION_LIFESPAN, v)),
            attribute("passwordSignIn", "Allow password sign-in (active WS-Trust)",
                    "Lets this client receive tokens from the active WS-Trust endpoint, which takes a"
                            + " username and password. The same switch as Direct access grants; the"
                            + " browser sign-in is not affected.",
                    ProviderConfigProperty.BOOLEAN_TYPE, null, "false",
                    c -> Boolean.toString(c.isDirectAccessGrantsEnabled()),
                    (c, v) -> c.setDirectAccessGrantsEnabled(Boolean.parseBoolean(v))),
            flag("jwt", "Issue a JWT instead of SAML",
                    "Issues a JWT in place of a SAML assertion. Leave off for AD FS style relying parties.",
                    WSFedLoginProtocol.WSFED_JWT),
            flag("x5t", "Include x5t in the JWT", "Adds the certificate thumbprint to a JWT.",
                    WSFedLoginProtocol.WSFED_X5T),
            flag("encrypt", "Encrypt assertions",
                    "Encrypts the SAML 2.0 assertion with the certificate below. SAML 1.1 cannot be encrypted.",
                    "saml.encrypt"),
            attribute("encryptionCertificate", "Encryption certificate",
                    "The relying party's public certificate, as PEM or its Base64 body. Needed for the Dynamics 365"
                            + " SDK: the proof key its token carries is encrypted with it. Dynamics 365 publishes it in its"
                            + " federation metadata as the encryption key.",
                    ProviderConfigProperty.TEXT_TYPE, null, null,
                    c -> c.getAttribute("saml.encryption.certificate"),
                    (c, v) -> c.setAttribute("saml.encryption.certificate", v)),
            attribute("encryptionKeySize", "Encryption key size",
                    "AES key size for an encrypted assertion.",
                    ProviderConfigProperty.LIST_TYPE, List.of("128", "192", "256"), "128",
                    c -> c.getAttribute(WSFedLoginProtocol.ENCRYPTION_KEY_SIZE),
                    (c, v) -> c.setAttribute(WSFedLoginProtocol.ENCRYPTION_KEY_SIZE, v)),
            attribute("logoutUrl", "Sign-out cleanup URL",
                    "Where wsignoutcleanup1.0 is sent when the user signs out elsewhere. Leave empty to use"
                            + " the first valid redirect URI.",
                    ProviderConfigProperty.STRING_TYPE, null, null,
                    c -> c.getAttribute(WSFedLoginProtocol.LOGOUT_URL_ATTRIBUTE),
                    (c, v) -> c.setAttribute(WSFedLoginProtocol.LOGOUT_URL_ATTRIBUTE, v)));

    private static ConsoleSetting<ClientModel> flag(String key, String label, String help, String attribute) {
        return attribute(key, label, help, ProviderConfigProperty.BOOLEAN_TYPE, null, "false",
                c -> c.getAttribute(attribute), (c, v) -> c.setAttribute(attribute, v));
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getHelpText() {
        return "Settings of each WS-Federation client that the client pages do not show";
    }

    @Override
    public Map<String, Object> getTypeMetadata() {
        return Map.of("displayFields", List.of(CLIENT_ID));
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>();
        ProviderConfigProperty client = new ProviderConfigProperty();
        client.setName(CLIENT_ID);
        client.setLabel("Client");
        client.setHelpText("The WS-Federation client these settings belong to.");
        client.setType(ProviderConfigProperty.CLIENT_LIST_TYPE);
        client.setRequired(true);
        properties.add(client);
        properties.addAll(SETTINGS.stream().map(ConsoleSetting::property).collect(Collectors.toList()));
        return properties;
    }

    static ClientModel clientOf(RealmModel realm, ComponentModel model) {
        String clientId = model.get(CLIENT_ID);
        return clientId == null || clientId.isBlank() ? null : realm.getClientByClientId(clientId.trim());
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {
        if (ConsoleMirror.isMirroring()) {
            return;
        }
        ClientModel client = clientOf(realm, model);
        if (client == null) {
            throw new ComponentValidationException("Choose an existing client.");
        }
        if (!WSFedLoginProtocol.LOGIN_PROTOCOL.equals(client.getProtocol())) {
            throw new ComponentValidationException("This page is for WS-Federation clients only.");
        }
        ConsoleMirror.refuseIfStale(realm, model);
        boolean duplicate = itemsOf(realm)
                .anyMatch(c -> !c.getId().equals(model.getId()) && client.getId().equals(c.getSubType()));
        if (duplicate) {
            throw new ComponentValidationException("This client already has an entry; edit that one.");
        }
        String lifespan = model.get("assertionLifespan", "").trim();
        if (!lifespan.isEmpty()) {
            try {
                if (Integer.parseInt(lifespan) < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new ComponentValidationException("The fixed token lifetime must be a whole number of seconds.");
            }
        }
    }

    @Override
    public void onCreate(KeycloakSession session, RealmModel realm, ComponentModel model) {
        saved(realm, null, model);
    }

    @Override
    public void onUpdate(KeycloakSession session, RealmModel realm, ComponentModel oldModel, ComponentModel newModel) {
        // An item moved to another client showed that client nothing, so all of it is its to take.
        boolean sameClient = oldModel != null && java.util.Objects.equals(oldModel.get(CLIENT_ID), newModel.get(CLIENT_ID));
        saved(realm, sameClient ? oldModel : null, newModel);
    }

    private void saved(RealmModel realm, ComponentModel shown, ComponentModel model) {
        if (ConsoleMirror.isMirroring()) {
            return;
        }
        ClientModel client = clientOf(realm, model);
        if (client == null) {
            return;
        }
        ConsoleMirror.applyChanges(shown, model, client, SETTINGS);
        // Record which client this item is for, and show the values as they now stand.
        refresh(realm, client);
    }

    static java.util.stream.Stream<ComponentModel> itemsOf(RealmModel realm) {
        return realm.getComponentsStream(realm.getId(), UiPageProvider.class.getName())
                .filter(c -> ID.equals(c.getProviderId()));
    }

    /** Brings the client's item in line with its attributes, creating it if it is missing. */
    static void refresh(RealmModel realm, ClientModel client) {
        if (!WSFedLoginProtocol.LOGIN_PROTOCOL.equals(client.getProtocol())) {
            remove(realm, client);
            return;
        }
        ComponentModel existing = itemsOf(realm)
                .filter(c -> client.getId().equals(c.getSubType()))
                .findFirst()
                .orElseGet(() -> itemsOf(realm)
                        .filter(c -> c.getSubType() == null && client.getClientId().equals(c.get(CLIENT_ID)))
                        .findFirst().orElse(null));
        if (existing == null) {
            ComponentModel created = new ComponentModel();
            created.setName(ConsoleMirror.revised(client.getClientId(), null));
            created.setProviderId(ID);
            created.setProviderType(UiPageProvider.class.getName());
            created.setParentId(realm.getId());
            // The client's internal id, so the item follows the client through a client id change
            created.setSubType(client.getId());
            created.setConfig(configFor(client));
            ConsoleMirror.mirroring(() -> realm.addComponentModel(created));
            return;
        }
        if (!ConsoleMirror.sameValues(existing, client, SETTINGS)
                || !client.getClientId().equals(existing.get(CLIENT_ID))
                || !client.getId().equals(existing.getSubType())) {
            existing.setName(ConsoleMirror.revised(client.getClientId(), existing));
            existing.setSubType(client.getId());
            existing.setConfig(configFor(client));
            ConsoleMirror.mirroring(() -> realm.updateComponent(existing));
        }
    }

    private static org.keycloak.common.util.MultivaluedHashMap<String, String> configFor(ClientModel client) {
        org.keycloak.common.util.MultivaluedHashMap<String, String> config = ConsoleMirror.configOf(client, SETTINGS);
        config.putSingle(CLIENT_ID, client.getClientId());
        return config;
    }

    static void remove(RealmModel realm, ClientModel client) {
        itemsOf(realm).filter(c -> client.getId().equals(c.getSubType()))
                .collect(Collectors.toList())
                .forEach(c -> ConsoleMirror.mirroring(() -> realm.removeComponent(c)));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (ConsoleMirror.isMirroring()) {
                return;
            }
            if (event instanceof ClientModel.ClientUpdatedEvent updated) {
                ClientModel client = updated.getUpdatedClient();
                ConsoleMirror.quietly("client " + client.getClientId(), () -> {
                    refresh(client.getRealm(), client);
                    return null;
                });
            } else if (event instanceof ClientModel.ClientRemovedEvent removed) {
                ClientModel client = removed.getClient();
                ConsoleMirror.quietly("client " + client.getClientId(), () -> {
                    remove(client.getRealm(), client);
                    return null;
                });
            } else if (event instanceof PostMigrationEvent) {
                ConsoleMirror.quietly("all clients", () -> {
                    KeycloakModelUtils.runJobInTransaction(factory, session -> session.realms().getRealmsStream()
                            .forEach(realm -> realm.getClientsStream()
                                    .filter(c -> WSFedLoginProtocol.LOGIN_PROTOCOL.equals(c.getProtocol()))
                                    .forEach(c -> refresh(realm, c))));
                    return null;
                });
            }
        });
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void close() {
    }
}
