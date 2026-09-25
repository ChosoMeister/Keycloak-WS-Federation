package io.github.chosomeister.keycloak.protocol.wsfed;

import org.junit.jupiter.api.Test;
import org.keycloak.saml.common.exceptions.ProcessingException;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The active endpoint reads SOAP sent by callers it does not control, so these cover the parts of
 * that handling where being wrong is expensive: entity expansion, and what is echoed back.
 */
class WSTrustSoapTest {

    private static final String ENVELOPE = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:a="http://www.w3.org/2005/08/addressing"
                        xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <s:Header>
                <a:MessageID>urn:uuid:abc</a:MessageID>
                <o:Security><o:UsernameToken>
                  <o:Username>alice</o:Username>
                  <o:Password>secret</o:Password>
                </o:UsernameToken></o:Security>
              </s:Header>
              <s:Body>
                <trust:RequestSecurityToken xmlns:trust="http://docs.oasis-open.org/ws-sx/ws-trust/200512"/>
              </s:Body>
            </s:Envelope>
            """;

    @Test
    void readsTheCredentialsAndRequestOutOfAnEnvelope() throws Exception {
        Document envelope = WSTrustSoap.parseEnvelope(ENVELOPE);

        assertEquals("alice", WSTrustSoap.text(
                WSTrustSoap.firstChild(envelope, WSTrustSoap.WSSE_NS, "Username")));
        assertEquals("secret", WSTrustSoap.text(
                WSTrustSoap.firstChild(envelope, WSTrustSoap.WSSE_NS, "Password")));
        assertEquals("urn:uuid:abc", WSTrustSoap.text(
                WSTrustSoap.firstChild(envelope, WSTrustSoap.ADDRESSING_NS, "MessageID")));
        assertNotNull(WSTrustSoap.firstChild(envelope, WSTrustSoap.TRUST_NS, "RequestSecurityToken"));
    }

    @Test
    void refusesADocumentTypeDeclaration() {
        // An entity expansion here would read files off the server or hang it.
        String withDoctype = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" + ENVELOPE;

        assertThrows(ProcessingException.class, () -> WSTrustSoap.parseEnvelope(withDoctype));
    }

    @Test
    void refusesInputThatIsNotXml() {
        assertThrows(ProcessingException.class, () -> WSTrustSoap.parseEnvelope("not xml at all"));
    }

    @Test
    void absentElementsReadAsNullRatherThanThrowing() throws Exception {
        Document envelope = WSTrustSoap.parseEnvelope(ENVELOPE);

        assertNull(WSTrustSoap.firstChild(envelope, WSTrustSoap.WSSE_NS, "BinarySecurityToken"));
        assertNull(WSTrustSoap.text(null));
    }

    @Test
    void theEchoedMessageIdCannotCarryMarkupBackToTheCaller() {
        String envelope = WSTrustSoap.envelope("<body/>", "urn:uuid:<script>alert(1)</script>");

        assertFalse(envelope.contains("<script>"), envelope);
        assertTrue(envelope.contains("&#60;"), envelope);
        assertTrue(envelope.contains(WSTrustSoap.ISSUE_FINAL_ACTION));
    }

    @Test
    void anEnvelopeWithoutAMessageIdOmitsRelatesTo() {
        assertFalse(WSTrustSoap.envelope("<body/>", null).contains("RelatesTo"));
    }

    @Test
    void faultsSayWhoIsAtFaultAndEscapeTheirReason() {
        assertTrue(WSTrustSoap.fault(true, "bad request").contains("s:Sender"));
        assertTrue(WSTrustSoap.fault(false, "server broke").contains("s:Receiver"));
        assertFalse(WSTrustSoap.fault(true, "<b>x</b>").contains("<b>"));
    }

    @Test
    void theCredentialIsTakenOnlyFromTheSecurityHeader() throws Exception {
        // A UsernameToken placed in the body is not the caller's credential.
        String misplaced = ENVELOPE
                .replace("<o:Security><o:UsernameToken>", "<o:Security><o:Other>")
                .replace("</o:UsernameToken></o:Security>", "</o:Other></o:Security>")
                .replace("<s:Body>", "<s:Body><o:UsernameToken><o:Username>mallory</o:Username>"
                        + "<o:Password>x</o:Password></o:UsernameToken>");

        assertNull(WSTrustSoap.usernameToken(WSTrustSoap.parseEnvelope(misplaced)));
        assertNotNull(WSTrustSoap.usernameToken(WSTrustSoap.parseEnvelope(ENVELOPE)));
    }

    @Test
    void theRequestIsTakenOnlyFromTheBody() throws Exception {
        assertNotNull(WSTrustSoap.requestSecurityToken(WSTrustSoap.parseEnvelope(ENVELOPE)));

        String inHeader = ENVELOPE
                .replace("<trust:RequestSecurityToken xmlns:trust=\"http://docs.oasis-open.org/ws-sx/ws-trust/200512\"/>", "")
                .replace("<s:Header>", "<s:Header><trust:RequestSecurityToken xmlns:trust=\"http://docs.oasis-open.org/ws-sx/ws-trust/200512\"/>");
        assertNull(WSTrustSoap.requestSecurityToken(WSTrustSoap.parseEnvelope(inHeader)));
    }

    @Test
    void onlySoap12EnvelopesAreRecognised() throws Exception {
        String soap11 = ENVELOPE.replace("http://www.w3.org/2003/05/soap-envelope", "http://schemas.xmlsoap.org/soap/envelope/");

        assertFalse(WSTrustSoap.isSoap12Envelope(WSTrustSoap.parseEnvelope(soap11)));
        assertTrue(WSTrustSoap.isSoap12Envelope(WSTrustSoap.parseEnvelope(ENVELOPE)));
        assertNull(WSTrustSoap.usernameToken(WSTrustSoap.parseEnvelope(soap11)));
    }
}
