package com.thabendra.telecom.customer;

import com.thabendra.telecom.subscriber.SubscriberRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Customer login with a one-time code by SMS (CU-1):
 * <ol>
 *   <li>{@link #requestCode}: a random 6-digit code, sent by SMS; Redis keeps only its HMAC, for 5 minutes</li>
 *   <li>{@link #verify}: the right code gives a customer token; the code then stops working</li>
 * </ol>
 * Guessing is held back three ways: 5 wrong codes delete the code, one new code per number per minute,
 * and the per-IP rate limit on the login (PR-3). The answer to a code request is the same whether the
 * number is ours or not, so the login can't be used to find out which numbers are customers.
 */
@Service
public class OtpService {

    private static final RedisScript<String> VERIFY =
            RedisScript.of(new ClassPathResource("redis/otp-verify.lua"), String.class);

    private final StringRedisTemplate redis;
    private final SubscriberRepository subscribers;
    private final SmsSender sms;
    private final CustomerTokens tokens;
    private final CustomerProperties.Otp otp;
    private final SecureRandom random = new SecureRandom();

    public OtpService(StringRedisTemplate redis, SubscriberRepository subscribers, SmsSender sms,
                      CustomerTokens tokens, CustomerProperties customers) {
        this.redis = redis;
        this.subscribers = subscribers;
        this.sms = sms;
        this.tokens = tokens;
        this.otp = customers.otp();
    }

    static String codeKey(String msisdn) {
        return "otp:" + msisdn;
    }

    static String cooldownKey(String msisdn) {
        return "otp:cooldown:" + msisdn;
    }

    public void requestCode(String msisdn) {
        // the cooldown comes first and counts for unknown numbers too, so it gives nothing away
        Boolean first = redis.opsForValue().setIfAbsent(cooldownKey(msisdn), "1", otp.cooldown());
        if (!Boolean.TRUE.equals(first)) {
            Long wait = redis.getExpire(cooldownKey(msisdn), TimeUnit.SECONDS);
            throw new OtpCooldownException(wait == null ? otp.cooldown().toSeconds() : Math.max(1, wait));
        }
        if (!subscribers.existsByMsisdn(msisdn)) {
            return;   // same 202 as for a customer, but no SMS
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        // a new code replaces the old one and starts with 0 wrong attempts
        redis.opsForHash().putAll(codeKey(msisdn), Map.of("hash", hash(msisdn, code), "attempts", "0"));
        redis.expire(codeKey(msisdn), otp.ttl());
        sms.send(msisdn, "Your telecom-ocs login code is " + code + ". It works for "
                + otp.ttl().toMinutes() + " minutes. Never share it with anyone.");
    }

    public CustomerTokens.IssuedToken verify(String msisdn, String code) {
        String result = redis.execute(VERIFY, List.of(codeKey(msisdn)),
                hash(msisdn, code), String.valueOf(otp.maxAttempts()));
        if (!"OK".equals(result)) {
            throw new InvalidOtpException();   // WRONG, LOCKED or MISSING: the same answer to the caller
        }
        return tokens.issue(msisdn);
    }

    // HMAC, not a plain hash: there are only a million codes, so a plain SHA-256 could be reversed
    // by trying them all if Redis ever leaked; without the secret that doesn't work
    private String hash(String msisdn, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otp.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((msisdn + ":" + code).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
