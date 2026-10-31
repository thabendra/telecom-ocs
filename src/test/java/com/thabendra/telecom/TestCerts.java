package com.thabendra.telecom;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
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
 *       already accepted that gateway's certificate, plus its access token</li>
 *   <li>{@link #https(int, String)}: a real HTTPS client for the mTLS port, showing that certificate
 *       and sending its access token</li>
 * </ul>
 */
public final class TestCerts {

    public static final String COLOMBO = "pgw-colombo-01";
    public static final String KANDY = "pgw-kandy-01";

    private static final Path DIR = Path.of("certs");
    private static final char[] PASSWORD = "changeit".toCharArray();

    private TestCerts() {
    }

    /**
     * MockMvc: the request comes with this gateway's verified certificate and a valid access token
     * for it (TestTokens). Added after another gateway(...), it replaces both.
     */
    public static RequestPostProcessor gateway(String name) {
        RequestPostProcessor certificate;
        try (InputStream pem = Files.newInputStream(file(name + ".pem"))) {
            certificate = x509((X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String token = TestTokens.token(name);
        return request -> {
            MockHttpServletRequest withCertificate = certificate.postProcessRequest(request);
            withCertificate.removeHeader(HttpHeaders.AUTHORIZATION);
            withCertificate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return withCertificate;
        };
    }

    /**
     * Real HTTPS to the gateway port on localhost, showing that certificate and sending a valid access
     * token for it; trusts only our CA and checks the host name.
     *
     * @param certificate key store name in certs/ (e.g. pgw-colombo-01, stranger), or null for none
     */
    public static RestClient https(int port, String certificate) {
        return https(port, certificate, certificate == null ? null : TestTokens.token(certificate));
    }

    /** Real HTTPS with this certificate and this access token (null: no Authorization header). */
    public static RestClient https(int port, String certificate, String token) {
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
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl("https://localhost:" + port)
                    .requestFactory(new JdkClientHttpRequestFactory(client));
            if (token != null) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return builder.build();
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
