package io.github.chosomeister.keycloak.protocol.wsfed;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request the Dynamics 365 SDK sends carries every element of the relying party's policy
 * template. None of them may make the request fail.
 */
class WSTrustRequestTest {

    private static final String ENTROPY = "c2FtcGxlLWNsaWVudC1lbnRyb3B5LTMyLWJ5dGVzLXRlc3Qh";

    /** The request body the SDK sends, as captured against Nova. */
    private static final String SDK_RST = """
            <trust:RequestSecurityToken xmlns:trust="http://docs.oasis-open.org/ws-sx/ws-trust/200512" xmlns:a="http://www.w3.org/2005/08/addressing">
              <wsp:AppliesTo xmlns:wsp="http://schemas.xmlsoap.org/ws/2004/09/policy"><a:EndpointReference><a:Address>https://crm.example.test/</a:Address></a:EndpointReference></wsp:AppliesTo>
              <trust:RequestType>http://docs.oasis-open.org/ws-sx/ws-trust/200512/Issue</trust:RequestType>
              <trust:KeyType>http://docs.oasis-open.org/ws-sx/ws-trust/200512/SymmetricKey</trust:KeyType>
              <trust:KeySize>256</trust:KeySize>
              <trust:KeyWrapAlgorithm>http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p</trust:KeyWrapAlgorithm>
              <trust:EncryptWith>http://www.w3.org/2001/04/xmlenc#aes256-cbc</trust:EncryptWith>
              <trust:SignWith>http://www.w3.org/2000/09/xmldsig#hmac-sha1</trust:SignWith>
              <trust:CanonicalizationAlgorithm>http://www.w3.org/2001/10/xml-exc-c14n#</trust:CanonicalizationAlgorithm>
              <trust:EncryptionAlgorithm>http://www.w3.org/2001/04/xmlenc#aes256-cbc</trust:EncryptionAlgorithm>
              <trust:Claims Dialect="http://schemas.xmlsoap.org/ws/2005/05/identity"><wsid:ClaimType Uri="http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn" xmlns:wsid="http://schemas.xmlsoap.org/ws/2005/05/identity"/></trust:Claims>
              <trust:Entropy><trust:BinarySecret Type="http://docs.oasis-open.org/ws-sx/ws-trust/200512/Nonce">%s</trust:BinarySecret></trust:Entropy>
              <trust:ComputedKeyAlgorithm>http://docs.oasis-open.org/ws-sx/ws-trust/200512/CK/PSHA1</trust:ComputedKeyAlgorithm>
            </trust:RequestSecurityToken>""".formatted(ENTROPY);

    private static Element element(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return doc.getDocumentElement();
    }

    @Test
    void readsTheFullSdkRequest() throws Exception {
        WSTrustRequest request = WSTrustRequest.parse(element(SDK_RST));

        assertEquals("https://crm.example.test/", request.appliesTo());
        assertTrue(request.wantsSymmetricKey());
        assertEquals(256, request.keySize());
        assertEquals(WSTrustRequest.PSHA1, request.computedKeyAlgorithm());
        assertArrayEquals(Base64.getDecoder().decode(ENTROPY), request.clientEntropy());
    }

    @Test
    void aMinimalRequestIsABearerRequest() throws Exception {
        WSTrustRequest request = WSTrustRequest.parse(element("""
                <trust:RequestSecurityToken xmlns:trust="http://docs.oasis-open.org/ws-sx/ws-trust/200512" xmlns:a="http://www.w3.org/2005/08/addressing">
                  <wsp:AppliesTo xmlns:wsp="http://schemas.xmlsoap.org/ws/2004/09/policy"><a:EndpointReference><a:Address>urn:rp</a:Address></a:EndpointReference></wsp:AppliesTo>
                </trust:RequestSecurityToken>"""));

        assertEquals("urn:rp", request.appliesTo());
        assertNull(request.keyType());
        assertNull(request.clientEntropy());
        assertEquals(0, request.keySize());
    }
}
