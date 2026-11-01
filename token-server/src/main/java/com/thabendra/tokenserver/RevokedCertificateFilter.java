package com.thabendra.tokenserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * SE-6: a revoked gateway gets no new token. The same signed CRL file the backend reads
 * (scripts/revoke-gateway.sh), read again on a schedule, so nothing restarts. A list that can't be read or
 * isn't signed by a CA we trust is refused, and the last good one stays.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // before Spring Security looks at the client
public class RevokedCertificateFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RevokedCertificateFilter.class);
    private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final Resource file;
    private final SslBundles bundles;
    private volatile X509CRL current;

    public RevokedCertificateFilter(TokenProperties properties, SslBundles bundles) {
        this.file = new DefaultResourceLoader().getResource(properties.revocation().location());
        this.bundles = bundles;
        reload();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/oauth2/token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        X509CRL crl = current;
        Object certificates = request.getAttribute(CERTIFICATE_ATTRIBUTE);
        if (crl != null && certificates instanceof X509Certificate[] chainOfCertificates
                && chainOfCertificates.length > 0 && crl.isRevoked(chainOfCertificates[0])) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"invalid_client\",\"error_description\":\"Certificate revoked\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Scheduled(fixedDelayString = "${telecom.token.revocation.reload-every}",
            initialDelayString = "${telecom.token.revocation.reload-every}")
    public final void reload() {
        try (InputStream in = file.getInputStream()) {
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(in);
            verify(crl);
            current = crl;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logger.error("Gateway revocation list " + file + " not loaded, keeping the last good one: " + e.getMessage());
        }
    }

    private void verify(X509CRL crl) throws GeneralSecurityException {
        KeyStore trusted = bundles.getBundle("token-server").getStores().getTrustStore();
        for (String alias : Collections.list(trusted.aliases())) {
            Certificate ca = trusted.getCertificate(alias);
            if (ca instanceof X509Certificate authority
                    && authority.getSubjectX500Principal().equals(crl.getIssuerX500Principal())) {
                crl.verify(authority.getPublicKey());
                return;
            }
        }
        throw new GeneralSecurityException("not signed by a CA we trust: " + crl.getIssuerX500Principal());
    }
}
