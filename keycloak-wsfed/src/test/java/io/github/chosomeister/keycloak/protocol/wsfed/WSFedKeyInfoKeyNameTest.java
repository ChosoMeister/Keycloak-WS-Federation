package io.github.chosomeister.keycloak.protocol.wsfed;

import io.github.chosomeister.keycloak.protocol.wsfed.builders.RequestSecurityTokenResponseBuilder;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.protocol.saml.SamlConfigAttributes;
import org.keycloak.saml.common.util.XmlKeyInfoKeyNameTransformer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WIF based relying parties, Dynamics 365 among them, fail with ID4037 when the signature's KeyInfo
 * carries a KeyName they cannot resolve. The signing key is therefore named according to the same
 * client attribute Keycloak's SAML protocol uses, so that a WS-Federation client is configured the
 * way an administrator already knows how to configure a SAML one.
 */
class WSFedKeyInfoKeyNameTest {

    private static ClientModel clientWithAttribute(String value) {
        Map<String, String> attributes = new HashMap<>();
        if (value != null) {
            attributes.put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, value);
        }

        InvocationHandler handler = (proxy, method, args) -> {
            if ("getAttribute".equals(method.getName()) && args != null && args.length == 1) {
                return attributes.get((String) args[0]);
            }
            if ("toString".equals(method.getName())) {
                return "client-stub";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            return null;
        };
        return (ClientModel) Proxy.newProxyInstance(
                ClientModel.class.getClassLoader(), new Class<?>[]{ClientModel.class}, handler);
    }

    private static XmlKeyInfoKeyNameTransformer resolve(String attributeValue) {
        return WSFedLoginProtocol.keyInfoKeyNameTransformer(clientWithAttribute(attributeValue));
    }

    @Test
    void defaultsToWhatASamlClientWouldGet() {
        // Keycloak's SAML protocol defaults to KEY_ID, and so must this one, otherwise upgrading
        // the extension would silently change every existing client's signature.
        assertEquals(XmlKeyInfoKeyNameTransformer.KEY_ID,
                RequestSecurityTokenResponseBuilder.DEFAULT_KEY_INFO_KEY_NAME_TRANSFORMER);
        assertEquals(XmlKeyInfoKeyNameTransformer.KEY_ID, resolve(null));
    }

    @Test
    void honoursEachSupportedValue() {
        assertEquals(XmlKeyInfoKeyNameTransformer.NONE, resolve("NONE"));
        assertEquals(XmlKeyInfoKeyNameTransformer.KEY_ID, resolve("KEY_ID"));
        assertEquals(XmlKeyInfoKeyNameTransformer.CERT_SUBJECT, resolve("CERT_SUBJECT"));
    }

    @Test
    void fallsBackToTheDefaultOnAnUnknownValue() {
        assertEquals(XmlKeyInfoKeyNameTransformer.KEY_ID, resolve("nonsense"));
        assertEquals(XmlKeyInfoKeyNameTransformer.KEY_ID, resolve(""));
    }

    @Test
    void theMatchIsExactSoNearMissesDoNotTakeEffect() {
        // These all leave the signature naming the key, which looks exactly like the attribute
        // never having been set. The resolver logs a warning so the difference is visible.
        for (String nearMiss : new String[]{"None", "none", " NONE ", "NONE ", "Cert_Subject"}) {
            assertEquals(XmlKeyInfoKeyNameTransformer.KEY_ID, resolve(nearMiss),
                    "'" + nearMiss + "' must not be treated as a recognised value");
        }
    }

    @Test
    void noneProducesNoKeyNameAtAll() {
        // A null key name is what makes the signer omit the KeyName element entirely.
        assertNull(XmlKeyInfoKeyNameTransformer.NONE.getKeyName("some-kid", null));
        assertEquals("some-kid", XmlKeyInfoKeyNameTransformer.KEY_ID.getKeyName("some-kid", null));
    }
}
