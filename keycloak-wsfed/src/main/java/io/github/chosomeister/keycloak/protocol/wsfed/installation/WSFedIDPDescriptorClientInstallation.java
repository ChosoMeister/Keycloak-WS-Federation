/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.chosomeister.keycloak.protocol.wsfed.installation;

import io.github.chosomeister.keycloak.protocol.wsfed.WSFedLoginProtocol;
import io.github.chosomeister.keycloak.protocol.wsfed.WSTrustActiveService;
import org.keycloak.Config;
import org.keycloak.common.util.PemUtils;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.models.*;
import org.keycloak.protocol.ClientInstallationProvider;
import org.keycloak.services.resources.RealmsResource;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class WSFedIDPDescriptorClientInstallation implements ClientInstallationProvider {

    /**
     * Returns the federation metadata document identifying the endpoint address as a SecurityTokenService
     * (see http://docs.oasis-open.org/wsfed/federation/v1.2/os/ws-federation-1.2-spec-os.html
     * section 3.1.2.2 SecurityTokenServiceType).
     *
     * FIXME replace lazy xml template substitution with JAXB handling .... probably.
     *
     * @return a string containing the xml for the wsfed metadata
     * @throws Exception IOException if there's a problem reading the wsfed-idp-metadata-template.xml
     */
    public static String getIDPDescriptorForClient(KeycloakSession session, RealmModel realm, URI uri) throws IOException {
        KeyManager keyManager = session.keys();
        KeyWrapper activeKey = keyManager.getActiveKey(realm, KeyUse.SIG, Algorithm.RS256);
        InputStream is = WSFedIDPDescriptorClientInstallation.class.getClassLoader().getResourceAsStream("wsfed-idp-metadata-template.xml");
        String template = "Error getting descriptor";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))){
            template = br.lines().collect(Collectors.joining("\n"));
            template = template.replace("${idp.entityID}", RealmsResource.realmBaseUrl(UriBuilder.fromUri(uri)).build(realm.getName()).toString());
            template = template.replace("${idp.sso.sts}", RealmsResource.protocolUrl(UriBuilder.fromUri(uri)).build(realm.getName(), WSFedLoginProtocol.LOGIN_PROTOCOL).toString());
            template = template.replace("${idp.sso.passive}", RealmsResource.protocolUrl(UriBuilder.fromUri(uri)).build(realm.getName(), WSFedLoginProtocol.LOGIN_PROTOCOL).toString());
            template = template.replace("${idp.signing.certificate}", PemUtils.encodeCertificate(activeKey.getCertificate()));
            template = template.replace("${idp.service.displayName}", serviceDisplayName(realm));
            template = template.replace("${idp.protocolSupportEnumeration}", protocolSupportEnumeration(realm));
            template = template.replace("${idp.claim.types}", renderClaimTypes(claimTypes(realm)));
        }
        return template;
    }

    /** Realm attribute listing the claim type URIs the descriptor advertises, whitespace separated. */
    public static final String CLAIM_TYPES_ATTRIBUTE = "wsfed.metadata.claim-types";

    /** Realm attribute opting the descriptor into announcing the WS-Trust namespaces. */
    public static final String ANNOUNCE_WS_TRUST_ATTRIBUTE = "wsfed.metadata.announce-ws-trust";

    private static final String WSFED_NS = "http://docs.oasis-open.org/wsfed/federation/200706";
    private static final String WS_TRUST_NS = "http://docs.oasis-open.org/ws-sx/ws-trust/200512";
    private static final String WS_TRUST_2005_NS = "http://schemas.xmlsoap.org/ws/2005/02/trust";

    /**
     * The claim types advertised when a realm does not say otherwise. These are what AD FS issues
     * to a Dynamics 365 relying party, which is the case this extension is most often deployed for.
     */
    private static final List<String> DEFAULT_CLAIM_TYPES = List.of(
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn",
            "http://schemas.microsoft.com/ws/2008/06/identity/claims/primarysid",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name");

    /** Readable names for the claim types this extension knows about. */
    private static final Map<String, String> CLAIM_DISPLAY_NAMES = Map.of(
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn", "UPN",
            "http://schemas.microsoft.com/ws/2008/06/identity/claims/primarysid", "Primary SID",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name", "Name",
            "http://schemas.microsoft.com/ws/2008/06/identity/claims/windowsaccountname", "Windows account name",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress", "E-Mail Address",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname", "Given Name",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname", "Surname",
            "http://schemas.microsoft.com/ws/2008/06/identity/claims/role", "Role");

    /**
     * This extension implements the passive requestor profile only. Announcing the WS-Trust
     * namespaces tells a relying party that an active endpoint exists, and a client that believes
     * it will look for one and fail, so it is off unless a realm asks for it.
     */
    static String protocolSupportEnumeration(RealmModel realm) {
        // Announced when the realm actually serves the active profile, so the descriptor stops
        // describing a capability that is not there. The attribute remains for a relying party
        // that needs the namespaces present for its own reasons.
        if (WSTrustActiveService.isEnabled(realm)
                || Boolean.parseBoolean(realm.getAttribute(ANNOUNCE_WS_TRUST_ATTRIBUTE))) {
            return WS_TRUST_NS + " " + WS_TRUST_2005_NS + " " + WSFED_NS;
        }
        return WSFED_NS;
    }

    /**
     * The claim types are an advertisement of what the security token service can issue, not a
     * promise about any one token: what a relying party receives is decided by the protocol
     * mappers on its client.
     */
    static List<String> claimTypes(RealmModel realm) {
        String configured = realm.getAttribute(CLAIM_TYPES_ATTRIBUTE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_CLAIM_TYPES;
        }

        List<String> claimTypes = Arrays.stream(configured.split("[\\s,]+"))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .collect(Collectors.toList());

        return claimTypes.isEmpty() ? DEFAULT_CLAIM_TYPES : claimTypes;
    }

    static String renderClaimTypes(List<String> claimTypes) {
        StringBuilder rendered = new StringBuilder();
        for (String uri : claimTypes) {
            String displayName = CLAIM_DISPLAY_NAMES.getOrDefault(uri, uri.substring(uri.lastIndexOf('/') + 1));
            rendered.append("\t\t\t<auth:ClaimType Uri=\"").append(escapeXmlAttribute(uri))
                    .append("\" Optional=\"true\">\n")
                    .append("\t\t\t\t<auth:DisplayName>").append(escapeXmlText(displayName))
                    .append("</auth:DisplayName>\n")
                    .append("\t\t\t</auth:ClaimType>\n");
        }
        return rendered.toString();
    }

    private static String escapeXmlText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeXmlAttribute(String value) {
        return escapeXmlText(value).replace("\"", "&quot;");
    }

    /**
     * WS-Federation metadata carries a human readable name for the security token service.
     * The realm display name is used when one is set, since that is what an administrator
     * recognises, falling back to the realm name.
     */
    private static String serviceDisplayName(RealmModel realm) {
        String displayName = realm.getDisplayName();
        String name = (displayName == null || displayName.isBlank()) ? realm.getName() : displayName;
        // The value lands in an XML attribute, so the delimiters have to be neutralised.
        return name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public Response generateInstallation(KeycloakSession session, RealmModel realm, ClientModel client, URI serverBaseUri) {
        String descriptor = null;
        try {
            descriptor = getIDPDescriptorForClient(session, realm, serverBaseUri);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok(descriptor, MediaType.TEXT_PLAIN_TYPE).build();
    }

    @Override
    public String getProtocol() {
        return WSFedLoginProtocol.LOGIN_PROTOCOL;
    }

    @Override
    public String getDisplayType() {
        return "WSFed Metadata IDP Descriptor";
    }

    @Override
    public String getHelpText() {
        return "WSFed Metadata.";
    }

    @Override
    public String getFilename() {
        return "wsfed-idp-metadata.xml";
    }

    public String getMediaType() {
        return MediaType.APPLICATION_XML;
    }

    @Override
    public boolean isDownloadOnly() {
        return false;
    }

    @Override
    public void close() {
        // Nothing to do
    }

    @Override
    public ClientInstallationProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
        // Nothing to do
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to do
    }

    @Override
    public String getId() {
        return "wsfed-idp-descriptor";
    }
}
