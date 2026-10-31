package com.thabendra.gatewaysim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.jks.JksSslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreDetails;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** With a TLS bundle the gateway talks HTTPS with its own certificate; without one, plain HTTP. */
class GatewayHttpTest {

    @Test
    void noBundleIsPlainHttp() {
        assertThat(GatewayHttp.requestFactory(Duration.ofMillis(200), null))
                .isInstanceOf(SimpleClientHttpRequestFactory.class);
    }

    @Test
    void bundleGivesAnHttpsClientWithTheGatewayCertificate() {
        // the test certificates from scripts/generate-dev-certs.sh (tests run in gateway-simulator/)
        SslBundle bundle = SslBundle.of(new JksSslStoreBundle(
                new JksSslStoreDetails("PKCS12", null, "file:../certs/pgw-colombo-01.p12", "changeit"),
                new JksSslStoreDetails("PKCS12", null, "file:../certs/gateway-ca-truststore.p12", "changeit")));

        assertThat(GatewayHttp.requestFactory(Duration.ofMillis(200), bundle))
                .isInstanceOf(JdkClientHttpRequestFactory.class);
        assertThat(bundle.getStores().getKeyStore()).isNotNull();   // the certificate file really loaded
    }
}
