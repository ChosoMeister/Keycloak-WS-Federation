package io.github.chosomeister.keycloak.protocol.wsfed.builders;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.saml.SamlConfigAttributes;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A relying party such as Dynamics 365 ties its session to the shortest validity in the token, so
 * a short assertion window signs the user out moments after they sign in. The window is set through
 * the same client attribute Keycloak's SAML protocol uses, with the same meaning.
 */
class AssertionLifespanTest {

    private static ClientModel client(String lifespan) {
        return client(lifespan, null);
    }

    private static ClientModel client(String lifespan, String fromSession) {
        Map<String, String> attributes = new HashMap<>();
        if (lifespan != null) {
            attributes.put(SamlConfigAttributes.SAML_ASSERTION_LIFESPAN, lifespan);
        }
        if (fromSession != null) {
            attributes.put(WsFedSAMLAssertionTypeAbstractBuilder.LIFESPAN_FROM_SESSION_ATTRIBUTE, fromSession);
        }
        return (ClientModel) Proxy.newProxyInstance(ClientModel.class.getClassLoader(),
                new Class<?>[]{ClientModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "getClientId" -> "urn:test";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static RealmModel realmWithTokenLifespan(int seconds) {
        return (RealmModel) Proxy.newProxyInstance(RealmModel.class.getClassLoader(),
                new Class<?>[]{RealmModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAccessTokenLifespan" -> seconds;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void anUnsetLifespanLeavesTheRealmDefaultsInPlace() {
        // So that upgrading changes nothing for a client that never asked for a different window.
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client(null)));
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client("")));
        assertEquals(300, WsFedSAMLAssertionTypeAbstractBuilder.tokenLifespan(realmWithTokenLifespan(300), client(null), null));
    }

    @Test
    void aConfiguredLifespanReplacesTheRealmDefault() {
        assertEquals(3600, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client("3600")));
        assertEquals(3600, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client(" 3600 ")));
    }

    @Test
    void theWsTrustLifetimeFollowsTheAssertionSoNeitherIsShorter() {
        // Extending the assertion alone would leave the RSTR Lifetime as the shortest window, and the
        // relying party would still end the session there.
        assertEquals(3600, WsFedSAMLAssertionTypeAbstractBuilder.tokenLifespan(realmWithTokenLifespan(300), client("3600"), null));
    }

    @Test
    void aValueThatIsNotAPositiveNumberOfSecondsIsIgnored() {
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client("0")));
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client("-60")));
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.configuredAssertionLifespan(client("1h")));
        assertEquals(300, WsFedSAMLAssertionTypeAbstractBuilder.tokenLifespan(realmWithTokenLifespan(300), client("1h"), null));
    }

    @Test
    void anExplicitLifespanTakesPrecedenceOverTheSession() {
        // An administrator who sets a number means that number, whatever the session would allow.
        assertEquals(600, WsFedSAMLAssertionTypeAbstractBuilder.effectiveAssertionLifespan(
                realmWithTokenLifespan(300), client("600", "true"), null));
    }

    @Test
    void theSessionIsFollowedOnlyWhenTheClientAsksForIt() {
        // Following the session is opt in, so a client that never asked keeps the realm defaults.
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.effectiveAssertionLifespan(
                realmWithTokenLifespan(300), client(null, null), null));
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.effectiveAssertionLifespan(
                realmWithTokenLifespan(300), client(null, "false"), null));
    }

    @Test
    void withoutASessionToFollowTheRealmDefaultsApply() {
        assertEquals(-1, WsFedSAMLAssertionTypeAbstractBuilder.effectiveAssertionLifespan(
                realmWithTokenLifespan(300), client(null, "true"), null));
        assertEquals(300, WsFedSAMLAssertionTypeAbstractBuilder.tokenLifespan(
                realmWithTokenLifespan(300), client(null, "true"), null));
    }
}
