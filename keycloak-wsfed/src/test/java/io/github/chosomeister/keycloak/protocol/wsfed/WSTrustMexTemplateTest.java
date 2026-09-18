package io.github.chosomeister.keycloak.protocol.wsfed;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A WCF client reads this document to work out how to call the active endpoint, and refuses the
 * call outright if the binding or the policy is not what it expects. These pin the parts it reads.
 */
class WSTrustMexTemplateTest {

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP12_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private static final String POLICY_NS = "http://www.w3.org/ns/ws-policy";
    private static final String SECURITY_POLICY_NS = "http://docs.oasis-open.org/ws-sx/ws-securitypolicy/200702";
    private static final String ENDPOINT = "https://keycloak.example.com/realms/Nova/protocol/wsfed/usernamemixed";

    private static String raw;
    private static Document wsdl;

    @BeforeAll
    static void render() throws Exception {
        InputStream stream = WSTrustMexTemplateTest.class.getClassLoader()
                .getResourceAsStream("wsfed-ws-trust-mex-template.xml");
        assertNotNull(stream, "the WS-Trust metadata template is missing from the build");

        raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("${wstrust.usernamemixed}", ENDPOINT);

        assertFalse(raw.contains("${"), "a placeholder was left unsubstituted");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        wsdl = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pointsAtTheActiveEndpoint() {
        Element address = (Element) wsdl.getElementsByTagNameNS(SOAP12_NS, "address").item(0);
        assertEquals(ENDPOINT, address.getAttribute("location"));

        assertEquals(1, wsdl.getElementsByTagNameNS("http://www.w3.org/2005/08/addressing", "Address").getLength());
    }

    @Test
    void describesTheIssueOperationOverSoap12() {
        assertEquals(1, wsdl.getElementsByTagNameNS(SOAP12_NS, "binding").getLength());

        Element operation = (Element) wsdl.getElementsByTagNameNS(SOAP12_NS, "operation").item(0);
        assertEquals("http://docs.oasis-open.org/ws-sx/ws-trust/200512/RST/Issue",
                operation.getAttribute("soapAction"));

        assertEquals(1, wsdl.getElementsByTagNameNS(WSDL_NS, "portType").getLength());
        assertEquals(1, wsdl.getElementsByTagNameNS(WSDL_NS, "service").getLength());
    }

    @Test
    void asksForAUsernameTokenOverTransportSecurity() {
        // Without these a WCF client does not know it may send a username and password.
        assertEquals(1, wsdl.getElementsByTagNameNS(SECURITY_POLICY_NS, "UsernameToken").getLength());
        assertEquals(1, wsdl.getElementsByTagNameNS(SECURITY_POLICY_NS, "WssUsernameToken10").getLength());
        assertEquals(1, wsdl.getElementsByTagNameNS(SECURITY_POLICY_NS, "TransportBinding").getLength());
        assertEquals(1, wsdl.getElementsByTagNameNS(SECURITY_POLICY_NS, "Trust13").getLength());
    }

    @Test
    void thePolicyIsReferencedByTheBinding() {
        Element policy = (Element) wsdl.getElementsByTagNameNS(POLICY_NS, "Policy").item(0);
        String id = policy.getAttributeNS(
                "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd", "Id");
        assertFalse(id.isEmpty(), "the policy needs an id for the binding to reference");

        Element reference = (Element) wsdl.getElementsByTagNameNS(POLICY_NS, "PolicyReference").item(0);
        assertEquals("#" + id, reference.getAttribute("URI"));
    }
}
