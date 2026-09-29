package io.github.chosomeister.keycloak.protocol.wsfed.builders;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The requestor and the relying party must end up with the same key: one computes it from the two
 * entropies, the other decrypts it from the assertion.
 */
class ProofKeyTest {

    /** A throwaway relying party certificate and its key, generated only for this test. */
    private static X509Certificate certificate() throws Exception {
        try (java.io.InputStream in = ProofKeyTest.class.getResourceAsStream("/proof-key/rp.crt")) {
            return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static java.security.PrivateKey privateKey() throws Exception {
        try (java.io.InputStream in = ProofKeyTest.class.getResourceAsStream("/proof-key/rp.pk8")) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            return java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        }
    }

    @Test
    void psha1MatchesTheTlsDefinition() {
        // P_SHA-1 output must be a prefix of any longer output with the same inputs.
        byte[] secret = "secret".getBytes(StandardCharsets.US_ASCII);
        byte[] seed = "seed".getBytes(StandardCharsets.US_ASCII);
        byte[] shortKey = ProofKey.psha1(secret, seed, 16);
        byte[] longKey = ProofKey.psha1(secret, seed, 64);
        assertArrayEquals(shortKey, java.util.Arrays.copyOf(longKey, 16));
        assertEquals(64, longKey.length);
    }

    @Test
    void theRelyingPartyRecoversTheKeyTheRequestorComputes() throws Exception {
        byte[] clientEntropy = new byte[32];
        new java.security.SecureRandom().nextBytes(clientEntropy);

        ProofKey proof = ProofKey.issue(clientEntropy, 256, certificate());

        Document assertion = javax.xml.parsers.DocumentBuilderFactory.newDefaultNSInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream("""
                        <saml:Assertion xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"><saml:Subject>
                          <saml:NameID>u</saml:NameID>
                          <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer"><saml:SubjectConfirmationData NotOnOrAfter="2030-01-01T00:00:00Z"/></saml:SubjectConfirmation>
                        </saml:Subject></saml:Assertion>""".getBytes(StandardCharsets.UTF_8)));
        proof.confirmSaml2(assertion);

        Element confirmation = (Element) assertion.getElementsByTagNameNS(ProofKey.SAML2_NS, "SubjectConfirmation").item(0);
        assertEquals("urn:oasis:names:tc:SAML:2.0:cm:holder-of-key", confirmation.getAttribute("Method"));
        Element cipherValue = (Element) assertion.getElementsByTagNameNS(ProofKey.XMLENC_NS, "CipherValue").item(0);
        assertNotNull(cipherValue);

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey());
        byte[] recovered = cipher.doFinal(Base64.getDecoder().decode(cipherValue.getTextContent()));

        assertArrayEquals(proof.key(), recovered);
        assertEquals(32, recovered.length);
    }
}
