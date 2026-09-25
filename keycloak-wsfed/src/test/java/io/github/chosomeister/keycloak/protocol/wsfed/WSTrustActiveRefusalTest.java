package io.github.chosomeister.keycloak.protocol.wsfed;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.OTPCredentialModel;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Once the password is known to be right, the active endpoint makes the same checks Keycloak's own
 * password grant does, so it does not become a way into an account that the browser flow would
 * still hold back.
 */
class WSTrustActiveRefusalTest {

    private static RealmModel realm(Map<String, String> attributes) {
        return (RealmModel) Proxy.newProxyInstance(RealmModel.class.getClassLoader(),
                new Class<?>[]{RealmModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> args.length == 1 ? attributes.get((String) args[0]) : null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static UserModel user(List<String> requiredActions, boolean hasOtp) {
        SubjectCredentialManager credentials = (SubjectCredentialManager) Proxy.newProxyInstance(
                SubjectCredentialManager.class.getClassLoader(), new Class<?>[]{SubjectCredentialManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isConfiguredFor" -> hasOtp && OTPCredentialModel.TYPE.equals(args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return (UserModel) Proxy.newProxyInstance(UserModel.class.getClassLoader(),
                new Class<?>[]{UserModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getRequiredActionsStream" -> requiredActions.stream();
                    case "credentialManager" -> credentials;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void aReadyAccountWithoutASecondFactorIsAccepted() {
        assertNull(WSTrustActiveService.refusalAfterPassword(realm(Map.of()), user(List.of(), false)));
    }

    @Test
    void anAccountWithAPendingRequiredActionIsRefused() {
        // Keycloak's password grant answers "Account is not fully set up" here.
        assertNotNull(WSTrustActiveService.refusalAfterPassword(realm(Map.of()), user(List.of("UPDATE_PASSWORD"), false)));
    }

    @Test
    void anAccountWithASecondFactorIsRefusedByDefault() {
        // Otherwise a stolen password alone would get past the second factor the browser enforces.
        assertNotNull(WSTrustActiveService.refusalAfterPassword(realm(Map.of()), user(List.of(), true)));
    }

    @Test
    void aRealmCanAllowPasswordOnlyForAccountsWithASecondFactor() {
        RealmModel allowing = realm(Map.of(WSTrustActiveService.ALLOW_PASSWORD_ONLY_ATTRIBUTE, "true"));
        assertNull(WSTrustActiveService.refusalAfterPassword(allowing, user(List.of(), true)));
    }

    @Test
    void allowingPasswordOnlyDoesNotWaiveRequiredActions() {
        RealmModel allowing = realm(Map.of(WSTrustActiveService.ALLOW_PASSWORD_ONLY_ATTRIBUTE, "true"));
        assertNotNull(WSTrustActiveService.refusalAfterPassword(allowing, user(List.of("UPDATE_PASSWORD"), true)));
    }
}
