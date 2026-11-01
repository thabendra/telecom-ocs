package com.thabendra.tokenserver;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** SE-7: the rotation steps, with a clock the test moves. */
class SigningKeyStoreTest {

    private static final Duration ROTATE_EVERY = Duration.ofDays(30);
    private static final Duration PUBLISH_AHEAD = Duration.ofMinutes(15);
    private static final Duration RETIRE_AFTER = Duration.ofMinutes(10);

    @TempDir
    private Path dir;

    private final MovableClock clock = new MovableClock();
    private TokenProperties.Signing settings;
    private SigningKeyStore store;

    @BeforeEach
    void setUp() {
        settings = new TokenProperties.Signing(dir.toString(), ROTATE_EVERY, PUBLISH_AHEAD, RETIRE_AFTER,
                Duration.ofMinutes(1));
        store = new SigningKeyStore(settings, clock);
    }

    @Test
    void theFirstKeySignsAtOnceBecauseNothingIsOutThereYet() {
        assertThat(published()).containsExactly(store.signingKeyId());
    }

    @Test
    void aRestartKeepsTheKey() {   // it used to make a new one, and refuse tokens for up to 30 s
        String key = store.signingKeyId();

        SigningKeyStore restarted = new SigningKeyStore(settings, clock);

        assertThat(restarted.signingKeyId()).isEqualTo(key);
        assertThat(restarted.get(everyKey(), null).get(0).isPrivate()).isTrue();
    }

    @Test
    void aNewKeyIsPublishedFirstAndSignsOnlyAfterTheBackendsHaveReadIt() {
        String first = store.signingKeyId();

        at(ROTATE_EVERY);
        assertThat(published()).hasSize(2).contains(first);
        assertThat(store.signingKeyId()).isEqualTo(first);

        at(ROTATE_EVERY.plus(PUBLISH_AHEAD).minusSeconds(1));
        assertThat(store.signingKeyId()).isEqualTo(first);

        at(ROTATE_EVERY.plus(PUBLISH_AHEAD));
        assertThat(store.signingKeyId()).isNotEqualTo(first);
        assertThat(published()).as("tokens it signed are still valid").contains(first);
    }

    @Test
    void theOldKeyGoesOnlyAfterItsLastTokenHasExpired() throws Exception {
        String first = store.signingKeyId();
        at(ROTATE_EVERY);   // the new key is made here

        at(ROTATE_EVERY.plus(PUBLISH_AHEAD).plus(RETIRE_AFTER).minusSeconds(1));
        assertThat(published()).contains(first);

        at(ROTATE_EVERY.plus(PUBLISH_AHEAD).plus(RETIRE_AFTER));
        assertThat(published()).containsExactly(store.signingKeyId()).doesNotContain(first);
        assertThat(files()).hasSize(1);
    }

    @Test
    void oneRoundMakesOneNewKey() throws Exception {
        at(ROTATE_EVERY);
        at(ROTATE_EVERY.plusSeconds(60));
        at(ROTATE_EVERY.plusSeconds(120));

        assertThat(files()).hasSize(2);
    }

    @Test
    void aBrokenFileIsIgnoredAndNotDeleted() throws Exception {
        String key = store.signingKeyId();
        Path broken = dir.resolve("1-broken.json");
        Files.writeString(broken, "{not a key");

        store.refresh();

        assertThat(store.signingKeyId()).isEqualTo(key);
        assertThat(broken).exists();
    }

    private void at(Duration sinceStart) {
        clock.now = MovableClock.START.plus(sinceStart);
        store.refresh();
    }

    private List<String> published() {
        return store.get(everyKey(), null).stream().map(JWK::getKeyID).toList();
    }

    private static JWKSelector everyKey() {
        return new JWKSelector(new JWKMatcher.Builder().build());
    }

    private List<Path> files() throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }

    private static final class MovableClock extends Clock {
        private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

        private Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
