package com.thabendra.telecom.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Which SOAP operation a request calls: a body from anybody, so it must never break the filter. */
class SoapOperationTest {

    private static final String ENVELOPE = """
            <e:Envelope xmlns:e="http://schemas.xmlsoap.org/soap/envelope/">
              <e:Header/><e:Body><s:StartSessionRequest xmlns:s="urn:x"/></e:Body>
            </e:Envelope>""";

    @Test
    void itIsTheFirstElementInTheBody() {
        assertThat(SoapOperation.of(bytes(ENVELOPE))).isEqualTo("StartSessionRequest");
    }

    @Test
    void anythingElseHasNoOperation() {
        assertThat(SoapOperation.of(bytes("not xml at all"))).isNull();
        assertThat(SoapOperation.of(bytes(""))).isNull();
        assertThat(SoapOperation.of(bytes("<e:Envelope xmlns:e=\"urn:e\"><e:Body/></e:Envelope>"))).isNull();
    }

    @Test
    void anExternalEntityIsNeverRead() {   // XXE
        String attack = """
                <!DOCTYPE e [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <e:Envelope xmlns:e="urn:e"><e:Body><s:A xmlns:s="urn:x">&x;</s:A></e:Body></e:Envelope>""";

        assertThat(SoapOperation.of(bytes(attack))).isNotEqualTo("root:x:0:0");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
