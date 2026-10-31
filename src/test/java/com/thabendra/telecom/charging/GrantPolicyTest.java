package com.thabendra.telecom.charging;

import com.thabendra.telecom.monitoring.OcsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CH-5 / RL-3 rules, with the Redis window answer faked: 100 MB, 250 MB for heavy users, or as asked. */
class GrantPolicyTest {

    private static final ChargingProperties PROPERTIES = new ChargingProperties(Duration.ofMinutes(65), false,
            new ChargingProperties.Grant(100, 250, 2048, Duration.ofMinutes(15)));

    private StringRedisTemplate redis;
    private GrantPolicy policy;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        UsageWindow window = new UsageWindow(redis, PROPERTIES, new OcsMetrics(new SimpleMeterRegistry()));
        policy = new GrantPolicy(window, PROPERTIES);
    }

    @Test
    void anAskedAmountIsGivenAsAsked() {
        windowHolds(5000);   // even for a heavy user

        assertThat(policy.size("94771234567", 500L, 0)).isEqualTo(500);
    }

    @Test
    void leftToUsANormalUserGetsTheDefault() {
        windowHolds(300);

        assertThat(policy.size("94771234567", null, 0)).isEqualTo(100);
    }

    @Test
    void aHeavyUserGetsTheBigGrant() {
        windowHolds(2048);

        assertThat(policy.size("94771234567", null, 0)).isEqualTo(250);
    }

    @Test
    void theUsageInThisVeryRequestCountsToo() {
        windowHolds(2000);

        assertThat(policy.size("94771234567", null, 0)).isEqualTo(100);
        assertThat(policy.size("94771234567", null, 100)).isEqualTo(250);   // 2000 + 100 >= 2048
    }

    @Test
    void withoutRedisNobodyIsHeavy() {   // never a reason to block: only normal grants
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(policy.size("94771234567", null, 5000)).isEqualTo(250);   // this request alone is heavy
        assertThat(policy.size("94771234567", null, 0)).isEqualTo(100);
    }

    @Test
    void logsNeverShowTheWholeNumber() {
        assertThat(UsageWindow.masked("94771234567")).isEqualTo("9477123****");
    }

    @SuppressWarnings("unchecked")
    private void windowHolds(long mb) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(mb);
    }
}
