package com.thabendra.telecom;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared clean-up: the test classes share one database and one Redis, so each test starts empty. */
public final class TestData {

    // children before parents, so foreign keys never block a delete
    private static final String[] TABLES = {"gateway_command", "quota_reservation", "charging_request",
            "charging_session", "usage_record", "subscriber_package", "plan_change", "subscriber",
            "data_package_price"};

    // packages a test created (codes start with TEST_); the seeded catalog stays
    public static final String TEST_PACKAGE_PREFIX = "TEST_";

    private TestData() {
    }

    public static void clean(JdbcTemplate jdbc, StringRedisTemplate redis) {
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM data_package WHERE code LIKE ?", TEST_PACKAGE_PREFIX + "%");
        flushRedis(redis);
    }

    public static void flushRedis(StringRedisTemplate redis) {
        redis.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
    }
}
