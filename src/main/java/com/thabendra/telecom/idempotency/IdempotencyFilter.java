package com.thabendra.telecom.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thabendra.telecom.web.ErrorResponses;
import com.thabendra.telecom.web.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Makes retried write calls safe (section H). A caller that times out and retries with the same
 * Idempotency-Key gets the first answer back instead of being charged twice.
 *
 * <ul>
 *   <li>ID-1: listed write endpoints need an Idempotency-Key header, otherwise 400</li>
 *   <li>ID-2/3: the first request runs and its response is stored; the same key again replays it</li>
 *   <li>ID-4: same key with a different method, path or body gives 422</li>
 *   <li>ID-5: a retry while the first request is still running gives 409 + Retry-After</li>
 *   <li>ID-6: keys are per caller: the gateway's certificate name on the mTLS port (it can't be faked;
 *       an X-Client-Id header is then ignored), on the public port the logged-in customer's number or
 *       staff login (group 6); the X-Client-Id header only when nobody is logged in</li>
 *   <li>ID-7: results are kept in Redis for resultTtl</li>
 *   <li>ID-9: only 2xx/4xx are stored; after a 5xx the key is dropped so the caller can retry</li>
 * </ul>
 * SOAP works the same way: every SOAP call is POST /ws, so the operation in soapenv:Body decides
 * (only the configured soapOperations, the charging writes, need a key). Errors to a SOAP caller are
 * a SOAP Client fault with the same HTTP status (400 / 409 + Retry-After / 422) instead of problem JSON.
 * The lookup and the lock are one Lua script call, so a hit and a miss each cost one Redis round trip.
 * If Redis can't be reached the request still runs; the Oracle request-number check (ID-8) still
 * stops a charging request from being charged twice.
 */
@Component
@Order(FilterOrder.IDEMPOTENCY)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String CLIENT_HEADER = "X-Client-Id";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Pattern VALID_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final String ANONYMOUS_CLIENT = "anonymous";

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final ObjectMapper json;
    private final AntPathMatcher paths = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyStore store, IdempotencyProperties properties, ObjectMapper json) {
        this.store = store;
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return !WRITE_METHODS.contains(request.getMethod())
                || (properties.paths().stream().noneMatch(pattern -> paths.match(pattern, path)) && !isSoap(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request);
        if (isSoap(request) && !properties.soapOperations().contains(SoapOperation.of(cached.body()))) {
            chain.doFilter(cached, response);   // SOAP reads and other operations need no key
            return;
        }

        String key = request.getHeader(KEY_HEADER);
        if (key == null || key.isBlank()) {
            problem(request, response, HttpStatus.BAD_REQUEST, "Missing " + KEY_HEADER + " header: send a new"
                    + " unique value (e.g. a UUID) with every write call, and the same value when retrying it");
            return;
        }
        if (!VALID_TOKEN.matcher(key).matches()) {
            problem(request, response, HttpStatus.BAD_REQUEST,
                    KEY_HEADER + " must be 1 to 100 letters, digits, '.', '_' or '-'");
            return;
        }
        String clientId = callerOf(request);

        String requestHash = fingerprint(request.getMethod(), pathOf(request), cached.body());
        String redisKey = IdempotencyStore.redisKey(clientId, key);

        // one Redis call: hit -> the stored record, miss -> lock taken and empty
        Optional<IdempotencyRecord> existing;
        try {
            existing = store.claimOrFind(redisKey, requestHash);
        } catch (DataAccessException redisDown) {
            log.warn("Redis unavailable, running {} {} without idempotency: {}",
                    request.getMethod(), pathOf(request), redisDown.getMessage());
            chain.doFilter(cached, response);
            return;
        }

        if (existing.isPresent()) {
            answerRepeat(request, response, existing.get(), requestHash);
            return;
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cached, captured);
        } catch (IOException | ServletException | RuntimeException e) {
            releaseQuietly(redisKey);
            throw e;
        }

        int status = captured.getStatus();
        if (status >= 500) {
            releaseQuietly(redisKey);
        } else {
            saveQuietly(redisKey, IdempotencyRecord.done(requestHash, status, captured.getContentType(),
                    new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8)));
        }
        captured.copyBodyToResponse();
    }

    private void answerRepeat(HttpServletRequest request, HttpServletResponse response, IdempotencyRecord record,
                              String requestHash) throws IOException {
        if (!record.requestHash().equals(requestHash)) {
            problem(request, response, HttpStatus.UNPROCESSABLE_ENTITY,
                    KEY_HEADER + " was already used for a different request: use a new key for a new request");
            return;
        }
        if (record.state() == IdempotencyRecord.State.IN_PROGRESS) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            problem(request, response, HttpStatus.CONFLICT, "Request with this " + KEY_HEADER + " is still being"
                    + " processed, retry after the Retry-After seconds");
            return;
        }
        response.setStatus(record.status());
        if (record.contentType() != null) {
            response.setContentType(record.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.getOutputStream().write(record.body().getBytes(StandardCharsets.UTF_8));
    }

    private void saveQuietly(String redisKey, IdempotencyRecord result) {
        try {
            store.saveResult(redisKey, result);
        } catch (DataAccessException e) {
            // the lock expires after lockTtl; until then retries get 409, after that the Oracle check applies
            log.warn("Could not store idempotent result for {}: {}", redisKey, e.getMessage());
        }
    }

    private void releaseQuietly(String redisKey) {
        try {
            store.release(redisKey);
        } catch (DataAccessException e) {
            log.warn("Could not release idempotency lock {}: {}", redisKey, e.getMessage());
        }
    }

    // a SOAP caller gets a SOAP fault, a REST caller problem JSON; the HTTP status is the same
    private void problem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ErrorResponses.write(request, response, status, detail, isSoap(request), json);
    }

    // Spring Security runs first, so the gateway / customer / staff name is already in the security context
    private static String callerOf(HttpServletRequest request) {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller != null && caller.isAuthenticated() && !(caller instanceof AnonymousAuthenticationToken)) {
            return caller.getName();
        }
        return Optional.ofNullable(request.getHeader(CLIENT_HEADER))
                .filter(id -> VALID_TOKEN.matcher(id).matches())
                .orElse(ANONYMOUS_CLIENT);
    }

    private boolean isSoap(HttpServletRequest request) {
        return paths.match(properties.soapPath(), pathOf(request));
    }

    /** SHA-256 of method + path + body: the same key must always mean exactly the same request (ID-4). */
    static String fingerprint(String method, String path, byte[] body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            sha.update(body);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
