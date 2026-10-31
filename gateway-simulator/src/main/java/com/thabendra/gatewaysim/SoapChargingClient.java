package com.thabendra.gatewaysim;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Charging over the backend's SOAP API (POST /ws, StartSession / UpdateSession / ReleaseSession).
 * Builds the SOAP 1.1 envelope by hand and reads the answer with DOM, so it needs no generated classes.
 * A SOAP fault comes back as HTTP 500: a Client fault is turned into status 400 (final, like a REST 4xx),
 * a Server fault stays 500. The backend's idempotency check answers SOAP with a Client fault and its own
 * status (400 / 409 + Retry-After / 422), which is kept as is, so a 409 still-in-progress is retried.
 * Like REST, every call carries the Idempotency-Key, and a replayed answer has Idempotent-Replayed: true.
 */
public class SoapChargingClient implements ChargingClient {

    static final String PATH = "/ws";
    static final String NAMESPACE = "http://thabendra.com/telecom/subscriber";
    static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";
    static final int CLIENT_FAULT = 400;
    static final int SERVER_FAULT = 500;

    private static final String ENVELOPE = """
            <soapenv:Envelope xmlns:soapenv="%s" xmlns:s="%s">
              <soapenv:Header/>
              <soapenv:Body>%s</soapenv:Body>
            </soapenv:Envelope>""";
    private static final MediaType TEXT_XML = new MediaType("text", "xml", StandardCharsets.UTF_8);

    /** The operation's response element and whether the backend replayed a stored answer. */
    private record SoapAnswer(Element content, boolean replayed) {
    }

    private final RestClient http;
    private final String clientId;

    public SoapChargingClient(RestClient.Builder builder, String backendUrl, String clientId) {
        this.http = builder.baseUrl(backendUrl).build();
        this.clientId = clientId;
    }

    @Override
    public Answer<Grant> start(String key, String msisdn, String app, long requestedMb) {
        SoapAnswer response = call(key, operation("StartSessionRequest",
                field("msisdn", msisdn), field("app", app), field("requestedMb", requestedMb)));
        return new Answer<>(grant(child(response.content(), "grant")), response.replayed());
    }

    @Override
    public Answer<Grant> update(String key, String sessionId, long requestNumber, long usedMb, long requestedMb) {
        SoapAnswer response = call(key, operation("UpdateSessionRequest", field("sessionId", sessionId),
                field("requestNumber", requestNumber), field("usedMb", usedMb), field("requestedMb", requestedMb)));
        return new Answer<>(grant(child(response.content(), "grant")), response.replayed());
    }

    @Override
    public Answer<Release> release(String key, String sessionId, long requestNumber, long usedMb) {
        SoapAnswer response = call(key, operation("ReleaseSessionRequest", field("sessionId", sessionId),
                field("requestNumber", requestNumber), field("usedMb", usedMb)));
        Element released = response.content();
        return new Answer<>(new Release(text(released, "sessionId"), Long.parseLong(text(released, "totalUsedMb"))),
                response.replayed());
    }

    // sends the envelope and returns the first element inside soapenv:Body (the ...Response element)
    private SoapAnswer call(String key, String payload) {
        try {
            ResponseEntity<String> response = http.post()
                    .uri(PATH)
                    .contentType(TEXT_XML)
                    .header("SOAPAction", "\"\"")
                    .header("Idempotency-Key", key)
                    .header("X-Client-Id", clientId)
                    .body(ENVELOPE.formatted(SOAP_ENV, NAMESPACE, payload))
                    .retrieve()
                    .toEntity(String.class);
            return new SoapAnswer(bodyContent(response.getBody()),
                    "true".equals(response.getHeaders().getFirst("Idempotent-Replayed")));
        } catch (ResourceAccessException e) {
            throw BackendCallException.noAnswer(e);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 500) {
                throw fault(e);
            }
            HttpHeaders headers = e.getResponseHeaders();
            throw new BackendCallException(status,
                    BackendCallException.parseRetryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER)),
                    "HTTP " + status + faultMessage(e), e);
        }
    }

    // the idempotency check's 400 / 409 / 422 carry a SOAP fault; show its text when there is one
    private static String faultMessage(RestClientResponseException e) {
        try {
            String message = text(bodyContent(e.getResponseBodyAsString()), "faultstring");
            return message == null ? "" : ": " + message;
        } catch (IllegalStateException notSoap) {
            return "";
        }
    }

    // <soapenv:Fault><faultcode>SOAP-ENV:Client</faultcode><faultstring>...</faultstring></soapenv:Fault>
    private static BackendCallException fault(RestClientResponseException e) {
        Element fault;
        try {
            fault = bodyContent(e.getResponseBodyAsString());
        } catch (IllegalStateException notSoap) {
            return new BackendCallException(SERVER_FAULT, null, "HTTP 500 without a SOAP fault", e);
        }
        String code = text(fault, "faultcode");
        String message = text(fault, "faultstring");
        boolean clientFault = code != null && code.endsWith("Client");
        return new BackendCallException(clientFault ? CLIENT_FAULT : SERVER_FAULT, null,
                "SOAP " + (clientFault ? "Client" : "Server") + " fault: " + message, e);
    }

    private static Grant grant(Element grant) {
        return new Grant(text(grant, "sessionId"), text(grant, "result"), Long.parseLong(text(grant, "grantedMb")),
                integer(grant, "bandwidthKbps"), text(grant, "source"), text(grant, "packageCode"),
                integer(grant, "validitySeconds"), text(grant, "action"));
    }

    private static String operation(String name, String... fields) {
        return "<s:" + name + ">" + String.join("", fields) + "</s:" + name + ">";
    }

    private static String field(String name, Object value) {
        return "<s:" + name + ">" + escape(String.valueOf(value)) + "</s:" + name + ">";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static Element bodyContent(String xml) {
        Node body = parse(xml).getElementsByTagNameNS(SOAP_ENV, "Body").item(0);
        if (body == null) {
            throw new IllegalStateException("Not a SOAP envelope: " + xml);
        }
        for (Node node = body.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                return element;
            }
        }
        throw new IllegalStateException("Empty SOAP body: " + xml);
    }

    // DOCTYPE is refused, so the answer can't pull in external entities (XXE)
    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable SOAP answer: " + xml, e);
        }
    }

    private static Element child(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String localName) {
        Element element = parent == null ? null : child(parent, localName);
        return element == null ? null : element.getTextContent().trim();
    }

    private static Integer integer(Element parent, String localName) {
        String value = text(parent, localName);
        return value == null ? null : Integer.valueOf(value);
    }
}
