package com.thabendra.telecom.charging;

import com.thabendra.telecom.monitoring.OcsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * RL-3: how much data one subscriber used in the last few minutes (redis/usage-window.lua), to find
 * heavy users. A heavy user gets bigger grants (CH-5: fewer requests for the same traffic) and shows
 * up in ocs_heavy_users_flagged_total for an alert. Being heavy never blocks anyone: the quota decides
 * that, as for everybody else. Redis down = nobody looks heavy, which only means normal grants.
 */
@Component
public class UsageWindow {

    private static final Logger log = LoggerFactory.getLogger(UsageWindow.class);

    private static final RedisScript<Long> WINDOW =
            RedisScript.of(new ClassPathResource("redis/usage-window.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final ChargingProperties.Grant grant;
    private final OcsMetrics metrics;

    public UsageWindow(StringRedisTemplate redis, ChargingProperties properties, OcsMetrics metrics) {
        this.redis = redis;
        this.grant = properties.grant();
        this.metrics = metrics;
    }

    static String key(String msisdn) {
        return "usage:" + msisdn;
    }

    /** MB used in the window, 0 when Redis can't be asked. */
    public long total(String msisdn) {
        return run(msisdn, 0);
    }

    public boolean isHeavy(long recentMb) {
        return recentMb >= grant.heavyThresholdMb();
    }

    /** Adds a usage report; the moment a subscriber crosses the threshold is counted once for the alert. */
    public long add(String msisdn, long usedMb) {
        if (usedMb <= 0) {
            return total(msisdn);
        }
        long total = run(msisdn, usedMb);
        if (!isHeavy(total - usedMb) && isHeavy(total)) {
            metrics.heavyUserFlagged();
            log.warn("heavy user {}: {} MB in the last {}, grants are now {} MB",
                    masked(msisdn), total, grant.heavyWindow(), grant.heavyMb());
        }
        return total;
    }

    private long run(String msisdn, long usedMb) {
        try {
            Long total = redis.execute(WINDOW, List.of(key(msisdn)), String.valueOf(usedMb),
                    String.valueOf(grant.heavyWindow().toMillis()), UUID.randomUUID().toString());
            return total == null ? 0 : total;
        } catch (DataAccessException redisDown) {
            log.debug("usage window not available for {}: {}", masked(msisdn), redisDown.getMessage());
            return 0;
        }
    }

    // a phone number is personal data: logs only show its start
    static String masked(String msisdn) {
        return msisdn.length() <= 7 ? "***" : msisdn.substring(0, msisdn.length() - 4) + "****";
    }
}
