package io.github.chosomeister.keycloak.common.wsfed.builders;

import jakarta.ws.rs.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WSFedResponseBuilderTest {

    @Test
    void escapesEveryUntrustedHtmlAttribute() {
        TestableBuilder builder = new TestableBuilder();
        builder.setMethod(HttpMethod.POST)
                .setDestination("https://rp.example/\" onmouseover=\"alert(1)")
                .setAction("<script>alert(2)</script>")
                .setRealm("realm&value")
                .setContext("\" autofocus onfocus=\"alert(3)");

        String html = builder.html("<token value=\"unsafe\">&</token>");
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("onmouseover=\"alert"));
        assertFalse(html.contains("onfocus=\"alert"));
        assertTrue(html.contains("&#34;"));
        assertTrue(html.contains("&#38;"));
    }

    @Test
    void rejectsUnexpectedFormMethods() {
        WSFedResponseBuilder builder = new WSFedResponseBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.setMethod("TRACE"));
    }

    private static final class TestableBuilder extends WSFedResponseBuilder {
        private String html(String result) {
            return buildHtml(destination, action, result, realm, context, username);
        }
    }
}
