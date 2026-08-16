package io.github.chosomeister.keycloak.broker.wsfed;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.events.EventBuilder;
import org.keycloak.saml.common.exceptions.ProcessingException;
import org.w3c.dom.Document;

import jakarta.ws.rs.core.Response;

import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The broker verifies the signature over the assertion it finds in the raw wresult, but reads the
 * claims from the assertion produced by the WS-Trust parser. Those are two independently parsed
 * copies, so a response carrying more than one assertion could have its signature checked against a
 * signed decoy while an unsigned assertion supplied the identity. Extraction must therefore accept
 * exactly one assertion.
 */
class AssertionExtractionTest {

    private static final String ASSERTION =
            "<saml:Assertion xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"%s\">"
                    + "<saml:Issuer>urn:test:issuer</saml:Issuer>"
                    + "</saml:Assertion>";

    /** Minimal implementation exposing the interface's default extraction helpers. */
    private static final class TestToken implements RequestedToken {
        @Override
        public Response validate(PublicKey key, WSFedIdentityProviderConfig config, EventBuilder event, KeycloakSession session) {
            return null;
        }

        @Override public String getUsername() { return null; }
        @Override public String getEmail() { return null; }
        @Override public String getId() { return null; }
        @Override public String getSessionIndex() { return null; }
        @Override public Object getToken() { return null; }
        @Override public String getFirstName() { return null; }
        @Override public String getLastName() { return null; }
    }

    private static String wrap(String body) {
        return "<wst:RequestSecurityTokenResponse xmlns:wst=\"http://docs.oasis-open.org/ws-sx/ws-trust/200512\">"
                + body + "</wst:RequestSecurityTokenResponse>";
    }

    @Test
    void singleAssertionIsExtracted() throws Exception {
        TestToken token = new TestToken();
        Document response = token.createXmlDocument(wrap(String.format(ASSERTION, "_id-1")));

        Document extracted = token.extractSamlDocument(response);

        assertEquals("Assertion", extracted.getDocumentElement().getLocalName());
        assertEquals("_id-1", extracted.getDocumentElement().getAttribute("ID"));
    }

    @Test
    void multipleAssertionsAreRejected() throws Exception {
        TestToken token = new TestToken();
        Document response = token.createXmlDocument(
                wrap(String.format(ASSERTION, "_signed") + String.format(ASSERTION, "_injected")));

        ProcessingException failure =
                assertThrows(ProcessingException.class, () -> token.extractSamlDocument(response));

        assertEquals(true, failure.getMessage().contains("exactly one Assertion"));
    }

    @Test
    void missingAssertionIsRejected() throws Exception {
        TestToken token = new TestToken();
        Document response = token.createXmlDocument(wrap("<wst:TokenType>urn:test</wst:TokenType>"));

        assertThrows(ProcessingException.class, () -> token.extractSamlDocument(response));
    }
}
