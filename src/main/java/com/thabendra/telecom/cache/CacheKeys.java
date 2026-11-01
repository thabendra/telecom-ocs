package com.thabendra.telecom.cache;

/** Every key in the cache. The v1 changes when a cached value's shape changes, so old values are never read. */
public final class CacheKeys {

    public static final String PLANS = "cache:v1:plans";
    public static final String PACKAGES = "cache:v1:packages";

    private CacheKeys() {
    }

    public static String profile(String msisdn) {
        return "cache:v1:profile:" + msisdn;
    }
}
