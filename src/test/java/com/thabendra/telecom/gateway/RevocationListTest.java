package com.thabendra.telecom.gateway;

import com.thabendra.telecom.monitoring.OcsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SE-6 with the real CRL files from scripts/generate-dev-certs.sh. */
class RevocationListTest {

    private static final Path CERTS = Path.of("certs");

    @TempDir
    private Path dir;

    private Path live;
    private SimpleMeterRegistry meters;
    private SslBundles bundles;

    @BeforeEach
    void setUp() throws Exception {
        live = dir.resolve("gateways.crl");
        meters = new SimpleMeterRegistry();
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(CERTS.resolve("gateway-ca-truststore.p12"))) {
            trusted.load(in, "changeit".toCharArray());
        }
        bundles = mock(SslBundles.class);
        when(bundles.getBundle("gateway-port")).thenReturn(SslBundle.of(SslStoreBundle.of(null, null, trusted)));
    }

    @Test
    void anEmptyListRevokesNobody() throws Exception {
        use("gateways.crl");

        assertThat(list().isRevoked(certificate("pgw-colombo-01"))).isFalse();
        assertThat(failures()).isZero();
    }

    @Test
    void onlyTheListedCertificateIsRevoked() throws Exception {
        use("test-crls/colombo-revoked.crl");
        RevocationList list = list();

        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isTrue();
        assertThat(list.isRevoked(certificate("pgw-kandy-01"))).isFalse();
    }

    @Test
    void aNewListWorksWithoutARestart() throws Exception {
        use("gateways.crl");
        RevocationList list = list();
        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isFalse();

        use("test-crls/colombo-revoked.crl");
        list.reload();
        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isTrue();

        use("gateways.crl");
        list.reload();
        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isFalse();
    }

    @Test
    void aListFromAnotherCaIsRefusedAndTheLastGoodOneStays() throws Exception {
        use("test-crls/colombo-revoked.crl");
        RevocationList list = list();

        use("test-crls/wrong-ca.crl");
        list.reload();

        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isTrue();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void aBrokenFileCannotUnrevokeAnybody() throws Exception {
        use("test-crls/colombo-revoked.crl");
        RevocationList list = list();

        Files.writeString(live, "-----BEGIN X509 CRL-----\nnot a list\n-----END X509 CRL-----\n");
        list.reload();
        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isTrue();

        Files.delete(live);
        list.reload();
        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isTrue();
        assertThat(failures()).isEqualTo(2);
    }

    @Test
    void withNoListAtAllNobodyIsRevokedAndTheFailureCounts() throws Exception {
        RevocationList list = list();

        assertThat(list.isRevoked(certificate("pgw-colombo-01"))).isFalse();
        assertThat(failures()).isEqualTo(1);
    }

    private RevocationList list() {
        return new RevocationList(new RevocationList.Properties(live.toUri().toString(), Duration.ofSeconds(30), false),
                bundles, new OcsMetrics(meters));
    }

    private void use(String file) throws Exception {
        Files.copy(CERTS.resolve(file), live, StandardCopyOption.REPLACE_EXISTING);
    }

    private double failures() {
        return meters.find("ocs.gateway.crl.failures").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static X509Certificate certificate(String name) throws Exception {
        try (InputStream pem = Files.newInputStream(CERTS.resolve(name + ".pem"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
    }
}
