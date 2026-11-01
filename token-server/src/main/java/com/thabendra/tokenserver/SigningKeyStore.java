package com.thabendra.tokenserver;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * SE-7: the signing keys, kept in a directory so a restart keeps them, and rotated with no failed token:
 * <pre>
 *   day 0     new key: published in /oauth2/jwks, not used yet
 *   +15 min   it starts signing: every backend has read it by now (they read the keys every 10 minutes)
 *   +25 min   the old key goes: the last token it signed (5 minutes) has expired
 * </pre>
 * One file per key, its name starting with its creation time. Several copies can share the directory: they
 * pick the same signing key from the same files, and at worst both make a key at rotation time, which only
 * means an extra key for one round. In production the directory would be a secret store's volume or a KMS.
 */
public class SigningKeyStore implements JWKSource<SecurityContext> {

    private record StoredKey(Path file, Instant created, RSAKey key) {
    }

    private record Published(JWKSet keys, String signingKeyId) {
    }

    private static final Logger log = LoggerFactory.getLogger(SigningKeyStore.class);

    private final TokenProperties.Signing settings;
    private final Clock clock;
    private final Path directory;
    private volatile Published published;

    public SigningKeyStore(TokenProperties.Signing settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.directory = Path.of(settings.directory());
        refresh();
    }

    /** Which key signs tokens now; the JWS header names it, as more than one key is published. */
    public String signingKeyId() {
        return published.signingKeyId();
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return selector.select(published.keys());
    }

    /** Makes a new key when the newest is old enough, picks the signing key, removes retired keys. */
    @Scheduled(fixedDelayString = "${telecom.token.signing.check-every}",
            initialDelayString = "${telecom.token.signing.check-every}")
    public final synchronized void refresh() {
        Instant now = clock.instant();
        List<StoredKey> keys = load();
        if (keys.isEmpty() || !keys.get(keys.size() - 1).created().plus(settings.rotateEvery()).isAfter(now)) {
            keys.add(create(now));
        }

        StoredKey signing = keys.get(0);
        for (StoredKey key : keys) {
            if (signingSince(key).compareTo(now) <= 0) {
                signing = key;
            }
        }

        List<JWK> kept = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            StoredKey key = keys.get(i);
            boolean replaced = i + 1 < keys.size()
                    && signingSince(keys.get(i + 1)).plus(settings.retireAfter()).compareTo(now) <= 0;
            if (replaced) {
                delete(key);
            } else {
                kept.add(key.key());
            }
        }
        String previous = published == null ? null : published.signingKeyId();
        published = new Published(new JWKSet(kept), signing.key().getKeyID());
        if (!signing.key().getKeyID().equals(previous)) {
            log.info("Signing tokens with key {}; {} keys published", signing.key().getKeyID(), kept.size());
        }
    }

    private Instant signingSince(StoredKey key) {
        return key.created().plus(settings.publishAhead());
    }

    private List<StoredKey> load() {
        try {
            Files.createDirectories(directory);
            try (Stream<Path> files = Files.list(directory)) {
                return new ArrayList<>(files.filter(file -> file.getFileName().toString().endsWith(".json"))
                        .map(this::read)
                        .filter(key -> key != null)
                        .sorted(Comparator.comparing(StoredKey::created))
                        .toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Signing key directory " + directory, e);
        }
    }

    private StoredKey read(Path file) {
        try {
            String name = file.getFileName().toString();
            Instant created = Instant.ofEpochSecond(Long.parseLong(name.substring(0, name.indexOf('-'))));
            return new StoredKey(file, created, RSAKey.parse(Files.readString(file)));
        } catch (IOException | ParseException | RuntimeException e) {
            log.error("Signing key file {} can't be read, ignoring it: {}", file, e.getMessage());
            return null;
        }
    }

    private StoredKey create(Instant now) {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            Path file = directory.resolve(now.getEpochSecond() + "-" + key.getKeyID() + ".json");
            Path temporary = directory.resolve(file.getFileName() + ".tmp");
            Files.writeString(temporary, key.toJSONString());
            restrict(temporary);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            log.info("New signing key {}: published now, signs from {}", key.getKeyID(),
                    now.plus(settings.publishAhead()));
            return new StoredKey(file, now, key);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not make a signing key", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store a signing key in " + directory, e);
        }
    }

    // private key material: only this user. Not available on Windows, where a test or a laptop runs
    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            log.debug("No POSIX permissions on {}", file);
        }
    }

    private void delete(StoredKey key) {
        try {
            Files.deleteIfExists(key.file());
            log.info("Retired signing key {}", key.key().getKeyID());
        } catch (IOException e) {
            log.warn("Retired signing key file {} not deleted: {}", key.file(), e.getMessage());
        }
    }
}
