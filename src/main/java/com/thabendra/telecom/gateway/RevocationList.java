package com.thabendra.telecom.gateway;

import com.thabendra.telecom.monitoring.OcsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;

/**
 * SE-6: revokes one gateway's certificate without touching the others or restarting anything. The list is
 * a standard CRL file signed by our CA (scripts/revoke-gateway.sh), read again every reloadEvery, so a
 * revocation works within that time on every backend copy.
 * <p>
 * A list that can't be read, or isn't signed by a CA we trust, is refused and the last good one stays: a bad
 * file must not un-revoke anybody. With no list at all, nobody is revoked and the failure alert fires; the
 * other choice, refusing every gateway, would turn one missing file into a network outage.
 * <p>
 * Checked per request and not in the TLS handshake: Tomcat only reads a CRL when the connector restarts.
 */
@Component
public class RevocationList {

    /**
     * telecom.gateways.revocation.* in application.yml.
     *
     * @param location    the CRL file
     * @param reloadEvery how soon a revocation works
     * @param reload      read it on a schedule (tests call {@link #reload()} themselves)
     */
    @ConfigurationProperties("telecom.gateways.revocation")
    public record Properties(String location, Duration reloadEvery, boolean reload) {
    }

    private static final Logger log = LoggerFactory.getLogger(RevocationList.class);

    private final Resource file;
    private final SslBundles bundles;
    private final OcsMetrics metrics;
    private volatile X509CRL current;

    public RevocationList(Properties properties, SslBundles bundles, OcsMetrics metrics) {
        this.file = new DefaultResourceLoader().getResource(properties.location());
        this.bundles = bundles;
        this.metrics = metrics;
        reload();
    }

    public boolean isRevoked(X509Certificate certificate) {
        X509CRL crl = current;
        return crl != null && crl.isRevoked(certificate);
    }

    /** Reads the file again; if anything is wrong with it, the last good list stays. */
    public final void reload() {
        try (InputStream in = file.getInputStream()) {
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(in);
            verify(crl);
            if (changed(crl)) {
                log.info("Gateway revocation list: {} certificates revoked, signed by {}",
                        crl.getRevokedCertificates() == null ? 0 : crl.getRevokedCertificates().size(),
                        crl.getIssuerX500Principal().getName());
            }
            current = crl;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            metrics.revocationListFailed();
            log.error("Gateway revocation list {} not loaded, keeping the last good one: {}", file, e.getMessage());
        }
    }

    private boolean changed(X509CRL crl) {
        return current == null || !current.equals(crl);
    }

    private void verify(X509CRL crl) throws GeneralSecurityException {
        KeyStore trusted = bundles.getBundle("gateway-port").getStores().getTrustStore();
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

    /** Reads the list on a schedule, unless telecom.gateways.revocation.reload is false. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "telecom.gateways.revocation.reload", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final RevocationList list;

        Schedule(RevocationList list) {
            this.list = list;
        }

        @Scheduled(fixedDelayString = "${telecom.gateways.revocation.reload-every}",
                initialDelayString = "${telecom.gateways.revocation.reload-every}")
        void run() {
            list.reload();
        }
    }
}
