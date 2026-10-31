package com.thabendra.telecom.idempotency;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;

/**
 * Finds which SOAP operation a request calls: the local name of the first element inside
 * soapenv:Body (e.g. StartSessionRequest). Every SOAP call goes to the same URL, so the path alone
 * can't tell a charging write from a read like GetQuota.
 */
final class SoapOperation {

    private static final XMLInputFactory XML = XMLInputFactory.newFactory();

    static {
        // no DOCTYPE or external entities from a request body (XXE)
        XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private SoapOperation() {
    }

    /** The operation element's local name, or null if the body isn't a readable SOAP envelope. */
    static String of(byte[] body) {
        XMLStreamReader reader = null;
        try {
            reader = XML.createXMLStreamReader(new ByteArrayInputStream(body));
            boolean inBody = false;
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    if (inBody) {
                        return reader.getLocalName();
                    }
                    inBody = "Body".equals(reader.getLocalName());
                }
            }
            return null;
        } catch (XMLStreamException notXml) {
            return null;   // the SOAP servlet answers it with its own fault
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing left to clean up
                }
            }
        }
    }
}
