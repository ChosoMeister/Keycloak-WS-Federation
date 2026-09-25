package io.github.chosomeister.keycloak.protocol.wsfed;

import org.keycloak.saml.common.exceptions.ProcessingException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.io.StringReader;

/**
 * Reads and writes the SOAP envelopes the active WS-Trust profile is carried in.
 *
 * <p>Only the few elements the profile needs are handled: there is no SOAP stack behind this, and
 * deliberately so. Pulling one in for a single request shape would add a large dependency and a
 * great deal of surface that this endpoint has no use for.
 */
public final class WSTrustSoap {

    public static final String SOAP12_NS = "http://www.w3.org/2003/05/soap-envelope";
    public static final String ADDRESSING_NS = "http://www.w3.org/2005/08/addressing";
    public static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    public static final String TRUST_NS = "http://docs.oasis-open.org/ws-sx/ws-trust/200512";

    public static final String PASSWORD_TEXT_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    public static final String ISSUE_FINAL_ACTION = TRUST_NS + "/RSTRC/IssueFinal";

    /** The largest request accepted, so a malformed or hostile body cannot exhaust memory. */
    public static final int MAX_REQUEST_BYTES = 256 * 1024;

    private WSTrustSoap() {
    }

    /**
     * Parses a SOAP envelope with every external entity facility switched off.
     *
     * @param body the raw request body
     * @return the parsed document
     * @throws ProcessingException when the body is not well formed XML
     */
    public static Document parseEnvelope(String body) throws ProcessingException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource source = new InputSource(new StringReader(body));
            return builder.parse(source);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ProcessingException("The request body is not a well formed SOAP envelope.", e);
        }
    }

    /**
     * @param parent element to search beneath
     * @param namespace the element's namespace
     * @param localName the element's local name
     * @return the first matching descendant, or null when there is none
     */
    public static Element firstChild(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    public static Element firstChild(Document document, String namespace, String localName) {
        NodeList nodes = document.getElementsByTagNameNS(namespace, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    /**
     * The UsernameToken of the request, taken only from where WS-Security puts it: the Security
     * header of a SOAP 1.2 envelope. A token found anywhere else, the body included, is not the
     * caller's credential and is ignored.
     *
     * @param envelope the parsed request
     * @return the UsernameToken, or null when the header carries none
     */
    public static Element usernameToken(Document envelope) {
        Element header = envelopeChild(envelope, "Header");
        Element security = directChild(header, WSSE_NS, "Security");
        return directChild(security, WSSE_NS, "UsernameToken");
    }

    /**
     * The RequestSecurityToken of the request, taken only from the body of a SOAP 1.2 envelope.
     *
     * @param envelope the parsed request
     * @return the RequestSecurityToken, or null when the body carries none
     */
    public static Element requestSecurityToken(Document envelope) {
        return directChild(envelopeChild(envelope, "Body"), TRUST_NS, "RequestSecurityToken");
    }

    /**
     * @param envelope the parsed request
     * @return whether the document is a SOAP 1.2 envelope, the only version this endpoint speaks
     */
    public static boolean isSoap12Envelope(Document envelope) {
        Element root = envelope.getDocumentElement();
        return root != null && SOAP12_NS.equals(root.getNamespaceURI()) && "Envelope".equals(root.getLocalName());
    }

    private static Element envelopeChild(Document envelope, String localName) {
        return isSoap12Envelope(envelope) ? directChild(envelope.getDocumentElement(), SOAP12_NS, localName) : null;
    }

    private static Element directChild(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    /**
     * @param element the element whose text is wanted
     * @return the element's text with surrounding whitespace removed, or null when absent or empty
     */
    public static String text(Element element) {
        if (element == null) {
            return null;
        }
        String content = element.getTextContent();
        if (content == null) {
            return null;
        }
        content = content.trim();
        return content.isEmpty() ? null : content;
    }

    /**
     * Serialises an element back to XML, used to hand the RequestSecurityToken to the WS-Trust
     * parser, which reads from a stream rather than a DOM.
     *
     * @param element the element to serialise
     * @return the element as XML
     * @throws ProcessingException when the element cannot be serialised
     */
    public static String toXml(Element element) throws ProcessingException {
        try {
            javax.xml.transform.Transformer transformer =
                    javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            java.io.StringWriter writer = new java.io.StringWriter();
            transformer.transform(new javax.xml.transform.dom.DOMSource(element),
                    new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (javax.xml.transform.TransformerException e) {
            throw new ProcessingException("Could not serialise the request element.", e);
        }
    }

    /**
     * Wraps an already serialised WS-Trust response in a SOAP envelope.
     *
     * @param responseBody the RequestSecurityTokenResponseCollection as XML
     * @param relatesTo the MessageID of the request, echoed back, or null when it carried none
     * @return the SOAP envelope
     */
    public static String envelope(String responseBody, String relatesTo) {
        StringBuilder envelope = new StringBuilder(responseBody.length() + 512);
        envelope.append("<s:Envelope xmlns:s=\"").append(SOAP12_NS)
                .append("\" xmlns:a=\"").append(ADDRESSING_NS).append("\">")
                .append("<s:Header>")
                .append("<a:Action s:mustUnderstand=\"1\">").append(ISSUE_FINAL_ACTION).append("</a:Action>");

        if (relatesTo != null) {
            // Echoed straight back to the caller, so it is escaped rather than trusted.
            envelope.append("<a:RelatesTo>").append(escape(relatesTo)).append("</a:RelatesTo>");
        }

        return envelope.append("</s:Header><s:Body>").append(responseBody)
                .append("</s:Body></s:Envelope>").toString();
    }

    /**
     * Builds a SOAP fault. The reason is deliberately coarse: a caller learns that the request was
     * refused, not which part of it was wrong.
     *
     * @param sender true when the caller is at fault, false when the server is
     * @param reason the human readable reason
     * @return the SOAP envelope carrying the fault
     */
    public static String fault(boolean sender, String reason) {
        return "<s:Envelope xmlns:s=\"" + SOAP12_NS + "\"><s:Body><s:Fault>"
                + "<s:Code><s:Value>" + (sender ? "s:Sender" : "s:Receiver") + "</s:Value></s:Code>"
                + "<s:Reason><s:Text xml:lang=\"en\">" + escape(reason) + "</s:Text></s:Reason>"
                + "</s:Fault></s:Body></s:Envelope>";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c > 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&') {
                escaped.append("&#").append((int) c).append(';');
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * @param node the node to read a namespace-qualified attribute from
     * @return the node's text, or null
     */
    public static String textOf(Node node) {
        return node == null ? null : text((Element) node);
    }
}
