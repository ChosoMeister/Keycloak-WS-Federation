package io.github.chosomeister.keycloak.protocol.wsfed;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A sign-out wreply is checked against the client's post logout redirect URIs, read the way
 * Keycloak reads them for its own clients.
 */
class LogoutRedirectsTest {

    private static final Set<String> REDIRECTS = Set.of("https://rp.example/callback");

    private static ClientModel client(String postLogout) {
        return (ClientModel) Proxy.newProxyInstance(ClientModel.class.getClassLoader(), new Class<?>[]{ClientModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> "post.logout.redirect.uris".equals(args[0]) ? postLogout : null;
                    case "getRedirectUris" -> REDIRECTS;
                    default -> null;
                });
    }

    @Test
    void unsetKeepsTheRedirectUris() {
        assertEquals(REDIRECTS, WSFedService.validLogoutRedirects(client(null)));
        assertEquals(REDIRECTS, WSFedService.validLogoutRedirects(client("")));
    }

    @Test
    void plusStandsForTheRedirectUris() {
        assertEquals(Set.of("https://rp.example/callback", "https://rp.example/bye"),
                WSFedService.validLogoutRedirects(client("+##https://rp.example/bye")));
    }

    @Test
    void explicitListReplacesTheRedirectUris() {
        assertEquals(Set.of("https://rp.example/bye"), WSFedService.validLogoutRedirects(client("https://rp.example/bye")));
    }

    @Test
    void minusAllowsNone() {
        assertEquals(Set.of(), WSFedService.validLogoutRedirects(client("-")));
    }
}
