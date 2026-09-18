package io.github.chosomeister.keycloak.protocol.wsfed.installation;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dynamics 365 claims-based authentication wizard validates the shape of the federation
 * metadata before it will accept an endpoint, so these assertions pin the elements that
 * WS-Federation relying parties of that kind rely on, and the realm attributes that steer them.
 */
class WSFedIDPDescriptorTemplateTest {

    private static final String MD_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
    private static final String FED_NS = "http://docs.oasis-open.org/wsfed/federation/200706";
    private static final String AUTH_NS = "http://docs.oasis-open.org/wsfed/authorization/200706";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

    private static RealmModel realm(Map<String, String> attributes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getAttribute" -> args != null && args.length == 1 ? attributes.get((String) args[0]) : null;
            case "getDisplayName" -> "Nova";
            case "getName" -> "Nova";
            case "toString" -> "realm-stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (RealmModel) Proxy.newProxyInstance(
                RealmModel.class.getClassLoader(), new Class<?>[]{RealmModel.class}, handler);
    }

    /** Renders the shipped template the way the installation provider does. */
    private static Document render(RealmModel realm) throws Exception {
        InputStream stream = WSFedIDPDescriptorTemplateTest.class.getClassLoader()
                .getResourceAsStream("wsfed-idp-metadata-template.xml");
        assertNotNull(stream, "metadata template is missing from the build");

        String rendered = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("${idp.entityID}", "https://keycloak.example.com/realms/Nova")
                .replace("${idp.sso.sts}", "https://keycloak.example.com/realms/Nova/protocol/wsfed")
                .replace("${idp.sso.passive}", "https://keycloak.example.com/realms/Nova/protocol/wsfed")
                .replace("${idp.signing.certificate}", "MIIBase64==")
                .replace("${idp.service.displayName}", "Nova")
                .replace("${idp.protocolSupportEnumeration}",
                        WSFedIDPDescriptorClientInstallation.protocolSupportEnumeration(realm))
                .replace("${idp.claim.types}", WSFedIDPDescriptorClientInstallation.renderClaimTypes(
                        WSFedIDPDescriptorClientInstallation.claimTypes(realm)));

        assertFalse(rendered.contains("${"), "a placeholder was left unsubstituted:\n" + rendered);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> attributeValues(Document doc, String ns, String element, String attribute) {
        NodeList nodes = doc.getElementsByTagNameNS(ns, element);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(((Element) nodes.item(i)).getAttribute(attribute));
        }
        return values;
    }

    private static String protocols(Document doc) {
        return ((Element) doc.getElementsByTagNameNS(MD_NS, "RoleDescriptor").item(0))
                .getAttribute("protocolSupportEnumeration");
    }

    @Test
    void offersBothSamlTokenTypes() throws Exception {
        List<String> offered = attributeValues(render(realm(Map.of())), FED_NS, "TokenType", "Uri");

        // WS-Federation and WIF spell a SAML 1.1 assertion with a "1.0" URI.
        assertTrue(offered.contains("urn:oasis:names:tc:SAML:1.0:assertion"), offered.toString());
        assertTrue(offered.contains("urn:oasis:names:tc:SAML:2.0:assertion"), offered.toString());
    }

    @Test
    void advertisesTheClaimsTheHelperScriptIssuesByDefault() throws Exception {
        List<String> offered = attributeValues(render(realm(Map.of())), AUTH_NS, "ClaimType", "Uri");

        // UPN is the claim a Dynamics 365 relying party matches its systemuser records on.
        assertTrue(offered.contains("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn"), offered.toString());
        assertTrue(offered.contains("http://schemas.microsoft.com/ws/2008/06/identity/claims/primarysid"), offered.toString());
        assertTrue(offered.contains("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"), offered.toString());
    }

    @Test
    void aRealmCanReplaceTheAdvertisedClaims() throws Exception {
        Document doc = render(realm(Map.of(
                WSFedIDPDescriptorClientInstallation.CLAIM_TYPES_ATTRIBUTE,
                "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress"
                        + " http://example.test/claims/custom")));

        assertEquals(List.of("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                        "http://example.test/claims/custom"),
                attributeValues(doc, AUTH_NS, "ClaimType", "Uri"));

        // A claim the extension does not know is still given a readable name.
        NodeList names = doc.getElementsByTagNameNS(AUTH_NS, "DisplayName");
        assertEquals("E-Mail Address", names.item(0).getTextContent());
        assertEquals("custom", names.item(1).getTextContent());
    }

    @Test
    void wsTrustIsNotAnnouncedUnlessTheRealmAsksForIt() throws Exception {
        // Only the passive requestor profile is implemented. A relying party told that WS-Trust
        // is available looks for an active endpoint that does not exist.
        String defaultProtocols = protocols(render(realm(Map.of())));
        assertEquals(FED_NS, defaultProtocols);

        String announced = protocols(render(realm(Map.of(
                WSFedIDPDescriptorClientInstallation.ANNOUNCE_WS_TRUST_ATTRIBUTE, "true"))));
        assertTrue(announced.contains("http://docs.oasis-open.org/ws-sx/ws-trust/200512"), announced);
        assertTrue(announced.contains("http://schemas.xmlsoap.org/ws/2005/02/trust"), announced);
        assertTrue(announced.contains(FED_NS), announced);
    }

    @Test
    void publishesTheSigningCertificateAndEndpoints() throws Exception {
        Document doc = render(realm(Map.of()));

        assertEquals("signing",
                ((Element) doc.getElementsByTagNameNS(MD_NS, "KeyDescriptor").item(0)).getAttribute("use"));
        // The certificate sits under a default-namespaced KeyInfo, matching what AD FS emits.
        assertEquals(1, doc.getElementsByTagNameNS(DSIG_NS, "X509Certificate").getLength());
        assertEquals(1, doc.getElementsByTagNameNS(FED_NS, "SecurityTokenServiceEndpoint").getLength());
        assertEquals(1, doc.getElementsByTagNameNS(FED_NS, "PassiveRequestorEndpoint").getLength());
    }

    @Test
    void anEmptyOrBlankClaimListFallsBackToTheDefaults() throws Exception {
        Map<String, String> blank = new HashMap<>();
        blank.put(WSFedIDPDescriptorClientInstallation.CLAIM_TYPES_ATTRIBUTE, "   ");

        assertEquals(3, attributeValues(render(realm(blank)), AUTH_NS, "ClaimType", "Uri").size());
    }
}
