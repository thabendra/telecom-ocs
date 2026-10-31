package com.thabendra.gatewaysim;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client both protocol clients share. With a TLS bundle (SE-1) it shows the gateway's
 * certificate from the bundle's key store, trusts only the CA in its trust store, and checks that the
 * backend's certificate matches the host name it calls. Every try keeps the per-try timeout (RT-8).
 */
public final class GatewayHttp {

    private GatewayHttp() {
    }

    public static ClientHttpRequestFactory requestFactory(Duration perTryTimeout, SslBundle tls) {
        if (tls == null) {
            SimpleClientHttpRequestFactory plain = new SimpleClientHttpRequestFactory();
            plain.setConnectTimeout(perTryTimeout);
            plain.setReadTimeout(perTryTimeout);
            return plain;
        }
        HttpClient https = HttpClient.newBuilder()
                .sslContext(tls.createSslContext())   // our certificate + private key, and the CA we trust
                .connectTimeout(perTryTimeout)
                .build();                             // host name verification is on by default
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(https);
        factory.setReadTimeout(perTryTimeout);
        return factory;
    }
}
