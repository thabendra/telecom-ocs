package com.thabendra.tokenserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SE-7: a new certificate file is picked up with no restart, while the server keeps its port. The backend's
 * gateway port uses the same Spring Boot setting (reload-on-update).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CertificateReloadTest {

    private static final Path DIR = Path.of("target", "certificate-reload-test");
    private static final Path KEYSTORE = DIR.resolve("token-server.p12");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void useACopyOfTheKeystore(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(DIR);
        Files.copy(Path.of("..", "certs", "token-server.p12"), KEYSTORE, StandardCopyOption.REPLACE_EXISTING);
        registry.add("spring.ssl.bundle.jks.token-server.keystore.location", () -> KEYSTORE.toUri().toString());
        registry.add("spring.ssl.bundle.watch.file.quiet-period", () -> "1s");
        registry.add("telecom.token.signing.directory", () -> "target/test-signing-keys");
    }

    @Test
    void aNewKeystoreFileIsServedWithoutARestart() throws Exception {
        assertThat(servedName()).contains("Token Server");

        Path rotated = DIR.resolve("rotated.p12");
        Files.deleteIfExists(rotated);
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "token-server", "-dname", "CN=localhost, O=Rotated", "-keyalg", "EC",
                "-groupname", "secp256r1", "-storetype", "PKCS12", "-storepass", "changeit", "-keystore",
                rotated.toString()).inheritIO().start();
        assertThat(keytool.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();
        Files.move(rotated, KEYSTORE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!servedName().contains("Rotated") && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertThat(servedName()).contains("Rotated");
    }

    // only looks at which certificate the server shows, so it trusts anything
    private String servedName() throws Exception {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket("localhost", port)) {
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSubjectX500Principal().getName();
        }
    }
}
