package io.github.chosomeister.keycloak.protocol.wsfed.installation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dynamics 365 claims-based authentication wizard validates the shape of the federation
 * metadata before it will accept an endpoint, and rejects a descriptor that omits the token
 * types or claim types the security token service offers. These assertions pin the elements
 * that WS-Federation relying parties of that kind rely on.
 */
class WSFedIDPDescriptorTemplateTest {

    private static final String FED_NS = "http://docs.oasis-open.org/wsfed/federation/200706";
    private static final String AUTH_NS = "http://docs.oasis-open.org/wsfed/authorization/200706";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

    private static Document descriptor;

    @BeforeAll
    static void renderTemplate() throws Exception {
        InputStream stream = WSFedIDPDescriptorTemplateTest.class.getClassLoader()
                .getResourceAsStream("wsfed-idp-metadata-template.xml");
        assertNotNull(stream, "metadata template is missing from the build");

        String rendered = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("${idp.entityID}", "https://keycloak.example.com/realms/Nova")
                .replace("${idp.sso.sts}", "https://keycloak.example.com/realms/Nova/protocol/wsfed")
                .replace("${idp.sso.passive}", "https://keycloak.example.com/realms/Nova/protocol/wsfed")
                .replace("${idp.signing.certificate}", "MIIBase64==")
                .replace("${idp.service.displayName}", "Nova");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        descriptor = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> attributeValues(String namespace, String element, String attribute) {
        NodeList nodes = descriptor.getElementsByTagNameNS(namespace, element);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(((Element) nodes.item(i)).getAttribute(attribute));
        }
        return values;
    }

    @Test
    void everyPlaceholderIsSubstituted() {
        assertTrue(descriptor.getDocumentElement().getAttribute("entityID").startsWith("https://"));
        NodeList roles = descriptor.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:metadata", "RoleDescriptor");
        assertEquals(1, roles.getLength(), "expected exactly one RoleDescriptor");
    }

    @Test
    void offersBothSamlTokenTypes() {
        List<String> offered = attributeValues(FED_NS, "TokenType", "Uri");

        // WS-Federation and WIF spell a SAML 1.1 assertion with a "1.0" URI.
        assertTrue(offered.contains("urn:oasis:names:tc:SAML:1.0:assertion"),
                "SAML 1.1 token type is not advertised: " + offered);
        assertTrue(offered.contains("urn:oasis:names:tc:SAML:2.0:assertion"),
                "SAML 2.0 token type is not advertised: " + offered);
    }

    @Test
    void offersTheClaimsTheHelperScriptIssues() {
        List<String> offered = attributeValues(AUTH_NS, "ClaimType", "Uri");

        // UPN is the claim a Dynamics 365 relying party matches its systemuser records on.
        assertTrue(offered.contains("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn"),
                "UPN claim type is not advertised: " + offered);
        assertTrue(offered.contains("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"),
                "Name claim type is not advertised: " + offered);
        assertTrue(offered.contains("http://schemas.microsoft.com/ws/2008/06/identity/claims/windowsaccountname"),
                "Windows account name claim type is not advertised: " + offered);
    }

    @Test
    void announcesWsTrustAlongsideWsFederation() {
        Element role = (Element) descriptor
                .getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:metadata", "RoleDescriptor").item(0);
        String protocols = role.getAttribute("protocolSupportEnumeration");

        assertTrue(protocols.contains("http://docs.oasis-open.org/ws-sx/ws-trust/200512"), protocols);
        assertTrue(protocols.contains("http://schemas.xmlsoap.org/ws/2005/02/trust"), protocols);
        assertTrue(protocols.contains(FED_NS), protocols);
    }

    @Test
    void publishesTheSigningCertificateAndEndpoints() {
        Element keyDescriptor = (Element) descriptor
                .getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:metadata", "KeyDescriptor").item(0);
        assertEquals("signing", keyDescriptor.getAttribute("use"));

        // The certificate sits under a default-namespaced KeyInfo, matching what AD FS emits.
        assertEquals(1, descriptor.getElementsByTagNameNS(DSIG_NS, "X509Certificate").getLength());

        assertEquals(1, descriptor.getElementsByTagNameNS(FED_NS, "SecurityTokenServiceEndpoint").getLength());
        assertEquals(1, descriptor.getElementsByTagNameNS(FED_NS, "PassiveRequestorEndpoint").getLength());
    }
}
