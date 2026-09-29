package io.github.chosomeister.keycloak.protocol.wsfed;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.Base64;

/**
 * The parts of a WS-Trust 1.3 RequestSecurityToken this endpoint acts on. Everything else a client
 * may send is ignored rather than refused: WCF clients, including the Dynamics 365 SDK, send what
 * the relying party's policy template lists, such as Claims, SignWith, EncryptWith,
 * CanonicalizationAlgorithm, EncryptionAlgorithm and KeyWrapAlgorithm, and the issued token does
 * not depend on any of them.
 *
 * @param appliesTo the relying party, from AppliesTo/EndpointReference/Address
 * @param keyType the requested key type, or null for the default (bearer)
 * @param keySize requested proof key size in bits, or 0 when absent
 * @param clientEntropy the requestor's entropy, or null when it sent none
 * @param computedKeyAlgorithm the requested computed key algorithm, or null
 */
record WSTrustRequest(String appliesTo, String keyType, int keySize, byte[] clientEntropy,
                      String computedKeyAlgorithm) {

    static final String TRUST_NS = "http://docs.oasis-open.org/ws-sx/ws-trust/200512";
    static final String POLICY_NS = "http://schemas.xmlsoap.org/ws/2004/09/policy";
    static final String POLICY_15_NS = "http://www.w3.org/ns/ws-policy";

    static final String BEARER = TRUST_NS + "/Bearer";
    static final String SYMMETRIC_KEY = TRUST_NS + "/SymmetricKey";
    static final String PUBLIC_KEY = TRUST_NS + "/PublicKey";
    static final String PSHA1 = TRUST_NS + "/CK/PSHA1";

    static WSTrustRequest parse(Element rst) {
        String appliesTo = null;
        Element applies = child(rst, POLICY_NS, "AppliesTo");
        if (applies == null) {
            applies = child(rst, POLICY_15_NS, "AppliesTo");
        }
        if (applies != null) {
            Element reference = child(applies, WSTrustSoap.ADDRESSING_NS, "EndpointReference");
            Element address = reference == null ? null : child(reference, WSTrustSoap.ADDRESSING_NS, "Address");
            appliesTo = text(address);
        }

        int keySize = 0;
        String size = text(child(rst, TRUST_NS, "KeySize"));
        if (size != null) {
            try {
                keySize = Integer.parseInt(size);
            } catch (NumberFormatException e) {
                keySize = -1;
            }
        }

        byte[] entropy = null;
        Element entropyElement = child(rst, TRUST_NS, "Entropy");
        String secret = entropyElement == null ? null : text(child(entropyElement, TRUST_NS, "BinarySecret"));
        if (secret != null) {
            try {
                entropy = Base64.getMimeDecoder().decode(secret);
            } catch (IllegalArgumentException e) {
                entropy = new byte[0];
            }
        }

        return new WSTrustRequest(appliesTo, text(child(rst, TRUST_NS, "KeyType")), keySize, entropy,
                text(child(rst, TRUST_NS, "ComputedKeyAlgorithm")));
    }

    boolean wantsSymmetricKey() {
        return SYMMETRIC_KEY.equals(keyType);
    }

    private static Element child(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
