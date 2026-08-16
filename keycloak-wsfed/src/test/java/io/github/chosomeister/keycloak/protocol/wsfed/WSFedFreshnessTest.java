package io.github.chosomeister.keycloak.protocol.wsfed;

import io.github.chosomeister.keycloak.common.wsfed.WSFedConstants;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * wfresh bounds how old the authentication behind an issued token may be, in minutes. Before this
 * was implemented the protocol always reported that no re-authentication was needed, silently
 * downgrading a relying party's step-up request to plain SSO.
 */
class WSFedFreshnessTest {

    /**
     * Both model interfaces are large and only two accessors matter here, so they are stubbed with
     * a proxy that answers those and returns null for everything else.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, String> notes) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (("getClientNote".equals(method.getName()) || "getNote".equals(method.getName()))
                    && args != null && args.length == 1) {
                return notes.get((String) args[0]);
            }
            if ("toString".equals(method.getName())) {
                return type.getSimpleName() + "-stub";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static AuthenticationSessionModel authSessionWithFreshness(String wfresh) {
        Map<String, String> notes = new HashMap<>();
        if (wfresh != null) {
            notes.put(WSFedConstants.WSFED_FRESHNESS, wfresh);
        }
        return stub(AuthenticationSessionModel.class, notes);
    }

    private static UserSessionModel userSessionAuthenticatedMinutesAgo(long minutes) {
        Map<String, String> notes = new HashMap<>();
        notes.put(AuthenticationManager.AUTH_TIME, String.valueOf(Time.currentTime() - (minutes * 60)));
        return stub(UserSessionModel.class, notes);
    }

    private static boolean requireReauthentication(String wfresh, UserSessionModel userSession) {
        return new WSFedLoginProtocol().requireReauthentication(userSession, authSessionWithFreshness(wfresh));
    }

    @Test
    void absentFreshnessImposesNoBound() {
        assertFalse(requireReauthentication(null, userSessionAuthenticatedMinutesAgo(600)));
        assertFalse(requireReauthentication("  ", userSessionAuthenticatedMinutesAgo(600)));
    }

    @Test
    void sessionWithinTheRequestedBoundIsAccepted() {
        assertFalse(requireReauthentication("30", userSessionAuthenticatedMinutesAgo(5)));
    }

    @Test
    void sessionOlderThanTheRequestedBoundRequiresReauthentication() {
        assertTrue(requireReauthentication("30", userSessionAuthenticatedMinutesAgo(45)));
    }

    @Test
    void freshnessOfZeroRequiresRecentAuthentication() {
        assertTrue(requireReauthentication("0", userSessionAuthenticatedMinutesAgo(1)));
    }

    @Test
    void unknownAuthenticationTimeRequiresReauthentication() {
        assertTrue(requireReauthentication("30", stub(UserSessionModel.class, new HashMap<>())));
        assertTrue(requireReauthentication("30", null));
    }

    @Test
    void malformedFreshnessIsIgnoredRatherThanLooping() {
        // Failing closed here would re-prompt on every pass and never converge.
        assertFalse(requireReauthentication("not-a-number", userSessionAuthenticatedMinutesAgo(600)));
        assertFalse(requireReauthentication("-1", userSessionAuthenticatedMinutesAgo(600)));
    }
}
