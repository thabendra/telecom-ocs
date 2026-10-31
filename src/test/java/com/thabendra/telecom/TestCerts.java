package com.thabendra.telecom;

import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;

/**
 * The test certificates in certs/ (made by scripts/generate-dev-certs.sh):
 * <ul>
 *   <li>{@link #gateway(String)}: for MockMvc, which skips real TLS; acts as if the handshake
 *       already accepted that gateway's certificate</li>
 *   <li>{@link #https(int, String)}: a real HTTPS client for the mTLS port, showing that certificate</li>
 * </ul>
 */
public final class TestCerts {

    public static final String COLOMBO = "pgw-colombo-01";
    public static final String KANDY = "pgw-kandy-01";

    private static final Path DIR = Path.of("certs");
    private static final char[] PASSWORD = "changeit".toCharArray();

    private TestCerts() {
    }

    /** MockMvc: the request comes with this gateway's verified certificate. */
    public static RequestPostProcessor gateway(String name) {
        try (InputStream pem = Files.newInputStream(file(name + ".pem"))) {
            return x509((X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Real HTTPS to the gateway port on localhost; trusts only our CA and checks the host name.
     *
     * @param certificate key store name in certs/ (e.g. pgw-colombo-01, stranger), or null for none
     */
    public static RestClient https(int port, String certificate) {
        try {
            KeyStore trust = load("gateway-ca-truststore");
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            KeyManagerFactory keyManagers = null;
            if (certificate != null) {
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(load(certificate), PASSWORD);
            }
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(keyManagers == null ? null : keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
            HttpClient client = HttpClient.newBuilder().sslContext(tls).build();
            return RestClient.builder()
                    .baseUrl("https://localhost:" + port)
                    .requestFactory(new JdkClientHttpRequestFactory(client))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Plain HTTP, e.g. to the public port. */
    public static RestClient http(int port) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Any status comes back as a response, so tests can look at 4xx / 5xx answers. */
    public static ResponseEntity<String> exchange(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(String.class);
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file(name + ".p12"))) {
            store.load(in, PASSWORD);
        }
        return store;
    }

    private static Path file(String name) {
        Path path = DIR.resolve(name);
        if (!Files.exists(path)) {
            throw new IllegalStateException(path + " is missing: run scripts/generate-dev-certs.sh from the project root");
        }
        return path;
    }
}
