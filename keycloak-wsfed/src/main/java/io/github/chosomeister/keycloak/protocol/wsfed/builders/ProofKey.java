package io.github.chosomeister.keycloak.protocol.wsfed.builders;

import org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityTokenResponse;
import org.picketlink.identity.federation.ws.trust.BinarySecretType;
import org.picketlink.identity.federation.ws.trust.ComputedKeyType;
import org.picketlink.identity.federation.ws.trust.EntropyType;
import org.picketlink.identity.federation.ws.trust.RequestedProofTokenType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;

/**
 * A symmetric proof key for a holder-of-key token, as WS-Trust 1.3 defines it for a request with
 * KeyType SymmetricKey. WCF clients, including the Dynamics 365 SDK, ask for one: they sign their
 * calls to the relying party with the key, and the relying party accepts the token only if it can
 * recover the same key from it.
 *
 * <p>The key reaches the two parties differently. The requestor gets it in the response: when it
 * sent entropy, as server entropy it combines with its own through P_SHA-1; otherwise as the key
 * itself. The relying party gets it inside the signed assertion, in the subject confirmation, as a
 * key encrypted with the relying party's own certificate, which is what AD FS issues.
 */
public final class ProofKey {

    public static final String TRUST_NS = "http://docs.oasis-open.org/ws-sx/ws-trust/200512";
    public static final String SYMMETRIC_KEY = TRUST_NS + "/SymmetricKey";
    public static final String PSHA1 = TRUST_NS + "/CK/PSHA1";
    static final String NONCE = TRUST_NS + "/Nonce";

    static final String XMLENC_NS = "http://www.w3.org/2001/04/xmlenc#";
    static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    static final String SAML2_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    static final String SAML11_NS = "urn:oasis:names:tc:SAML:1.0:assertion";

    static final String RSA_OAEP = XMLENC_NS + "rsa-oaep-mgf1p";
    static final String SHA1 = DSIG_NS + "sha1";
    static final String THUMBPRINT = "http://docs.oasis-open.org/wss/oasis-wss-soap-message-security-1.1#ThumbprintSHA1";
    static final String BASE64 = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final byte[] serverEntropy;
    private final int keySizeBits;
    private final X509Certificate relyingPartyCertificate;

    private ProofKey(byte[] key, byte[] serverEntropy, int keySizeBits, X509Certificate relyingPartyCertificate) {
        this.key = key;
        this.serverEntropy = serverEntropy;
        this.keySizeBits = keySizeBits;
        this.relyingPartyCertificate = relyingPartyCertificate;
    }

    /**
     * @param clientEntropy the requestor's entropy, or null to have the issuer choose the key
     * @param keySizeBits the key size, a multiple of 8
     * @param relyingPartyCertificate the certificate the relying party decrypts the key with
     */
    public static ProofKey issue(byte[] clientEntropy, int keySizeBits, X509Certificate relyingPartyCertificate) {
        int length = keySizeBits / 8;
        if (clientEntropy == null) {
            return new ProofKey(random(length), null, keySizeBits, relyingPartyCertificate);
        }
        byte[] serverEntropy = random(length);
        return new ProofKey(psha1(clientEntropy, serverEntropy, length), serverEntropy, keySizeBits,
                relyingPartyCertificate);
    }

    byte[] key() {
        return key.clone();
    }

    /**
     * P_SHA-1 from TLS 1.0, which WS-Trust names for a computed key: the requestor's entropy is the
     * secret and the issuer's entropy is the seed.
     */
    static byte[] psha1(byte[] secret, byte[] seed, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.length == 0 ? new byte[1] : secret, "HmacSHA1"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] a = seed;
            while (out.size() < length) {
                a = mac.doFinal(a);
                mac.update(a);
                out.writeBytes(mac.doFinal(seed));
            }
            return Arrays.copyOf(out.toByteArray(), length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** Adds what the requestor needs to arrive at the key to the response. */
    void applyTo(RequestSecurityTokenResponse response) {
        response.setKeyType(URI.create(SYMMETRIC_KEY));
        response.setKeySize(keySizeBits);
        RequestedProofTokenType proof = new RequestedProofTokenType();
        if (serverEntropy != null) {
            EntropyType entropy = new EntropyType();
            entropy.addAny(binarySecret(serverEntropy, NONCE));
            response.setEntropy(entropy);
            proof.add(new ComputedKeyType(PSHA1));
        } else {
            proof.add(binarySecret(key, null));
        }
        response.setRequestedProofToken(proof);
    }

    private static BinarySecretType binarySecret(byte[] value, String type) {
        BinarySecretType secret = new BinarySecretType();
        // The writer emits the bytes as they are, so they carry the base64 text.
        secret.setValue(Base64.getEncoder().encodeToString(value).getBytes(StandardCharsets.US_ASCII));
        if (type != null) {
            secret.setType(type);
        }
        return secret;
    }

    /**
     * Makes each subject of a SAML 2.0 assertion holder-of-key, with the encrypted key in its
     * confirmation data. Run before the assertion is signed, so the signature covers it.
     */
    void confirmSaml2(Document assertion) throws GeneralSecurityException {
        NodeList confirmations = assertion.getElementsByTagNameNS(SAML2_NS, "SubjectConfirmation");
        for (int i = 0; i < confirmations.getLength(); i++) {
            Element confirmation = (Element) confirmations.item(i);
            confirmation.setAttributeNS(null, "Method", "urn:oasis:names:tc:SAML:2.0:cm:holder-of-key");

            Element data = firstChild(confirmation, SAML2_NS, "SubjectConfirmationData");
            if (data == null) {
                data = assertion.createElementNS(SAML2_NS, prefixed(confirmation, "SubjectConfirmationData"));
                confirmation.appendChild(data);
            }
            String samlPrefix = confirmation.getPrefix();
            String typePrefix = samlPrefix == null ? "saml2" : samlPrefix;
            if (samlPrefix == null) {
                data.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:saml2", SAML2_NS);
            }
            data.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsi", XSI_NS);
            data.setAttributeNS(XSI_NS, "xsi:type", typePrefix + ":KeyInfoConfirmationDataType");
            data.appendChild(keyInfo(assertion));
        }
    }

    /**
     * Makes each subject of a SAML 1.1 assertion holder-of-key, with the encrypted key after its
     * confirmation methods. Run before the assertion is signed.
     */
    void confirmSaml11(Document assertion) throws GeneralSecurityException {
        NodeList confirmations = assertion.getElementsByTagNameNS(SAML11_NS, "SubjectConfirmation");
        for (int i = 0; i < confirmations.getLength(); i++) {
            Element confirmation = (Element) confirmations.item(i);
            NodeList methods = confirmation.getElementsByTagNameNS(SAML11_NS, "ConfirmationMethod");
            for (int m = 0; m < methods.getLength(); m++) {
                methods.item(m).setTextContent("urn:oasis:names:tc:SAML:1.0:cm:holder-of-key");
            }
            confirmation.appendChild(keyInfo(assertion));
        }
    }

    /** ds:KeyInfo holding the key encrypted with the relying party's certificate, as AD FS writes it. */
    private Element keyInfo(Document doc) throws GeneralSecurityException {
        Element keyInfo = doc.createElementNS(DSIG_NS, "ds:KeyInfo");
        keyInfo.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:ds", DSIG_NS);

        Element encryptedKey = doc.createElementNS(XMLENC_NS, "xenc:EncryptedKey");
        encryptedKey.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xenc", XMLENC_NS);
        keyInfo.appendChild(encryptedKey);

        Element method = doc.createElementNS(XMLENC_NS, "xenc:EncryptionMethod");
        method.setAttributeNS(null, "Algorithm", RSA_OAEP);
        Element digest = doc.createElementNS(DSIG_NS, "ds:DigestMethod");
        digest.setAttributeNS(null, "Algorithm", SHA1);
        method.appendChild(digest);
        encryptedKey.appendChild(method);

        Element certificateInfo = doc.createElementNS(DSIG_NS, "ds:KeyInfo");
        Element reference = doc.createElementNS(WSSE_NS, "o:SecurityTokenReference");
        reference.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:o", WSSE_NS);
        Element identifier = doc.createElementNS(WSSE_NS, "o:KeyIdentifier");
        identifier.setAttributeNS(null, "ValueType", THUMBPRINT);
        identifier.setAttributeNS(null, "EncodingType", BASE64);
        identifier.setTextContent(Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(relyingPartyCertificate.getEncoded())));
        reference.appendChild(identifier);
        certificateInfo.appendChild(reference);
        encryptedKey.appendChild(certificateInfo);

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, relyingPartyCertificate.getPublicKey());
        Element cipherData = doc.createElementNS(XMLENC_NS, "xenc:CipherData");
        Element cipherValue = doc.createElementNS(XMLENC_NS, "xenc:CipherValue");
        cipherValue.setTextContent(Base64.getEncoder().encodeToString(cipher.doFinal(key)));
        cipherData.appendChild(cipherValue);
        encryptedKey.appendChild(cipherData);

        return keyInfo;
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String prefixed(Element sibling, String localName) {
        return sibling.getPrefix() == null ? localName : sibling.getPrefix() + ":" + localName;
    }
}
