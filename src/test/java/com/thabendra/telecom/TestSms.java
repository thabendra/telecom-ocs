package com.thabendra.telecom;

import com.thabendra.telecom.customer.SmsSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches the login SMS in tests (CU-1), so a test can read the code the way a customer reads their
 * phone. Part of {@link IntegrationTest}, so every test class keeps sharing one Spring context.
 */
public class TestSms implements SmsSender {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final Map<String, String> lastText = new ConcurrentHashMap<>();

    @Override
    public void send(String msisdn, String text) {
        lastText.put(msisdn, text);
    }

    /** The code in the last SMS to this number, or null when none was sent. */
    public String lastCode(String msisdn) {
        String text = lastText.get(msisdn);
        if (text == null) {
            return null;
        }
        Matcher matcher = CODE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public void clear() {
        lastText.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        TestSms testSms() {
            return new TestSms();
        }
    }
}
