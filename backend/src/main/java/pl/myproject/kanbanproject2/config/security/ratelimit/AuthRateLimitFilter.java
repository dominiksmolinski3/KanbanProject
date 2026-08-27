package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.myproject.kanbanproject2.exception.ErrorResponse;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Throttles the four unauthenticated {@code /auth/**} endpoints.
 *
 * <p>Every request is charged twice, once against the caller's address and once against the email
 * it targets, and either bucket running dry ends the request with {@code 429} and a
 * {@code Retry-After}. The address bucket is checked first so a caller flooding the endpoint is
 * turned away before the filter spends anything reading their body.
 *
 * <p>It runs inside the Spring Security chain just after the CORS filter: early enough to sit in
 * front of everything these endpoints do, late enough that a rejection still carries the CORS
 * headers a cross-origin caller needs to read the status.
 */
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    /**
     * How much of the body is kept to find the target account in. The largest body these endpoints
     * accept is a signup — a 255-character email, a 72-character password and a 50-character
     * username — so 4 KiB is far more than a real request needs, and a body that pushes its email
     * past this limit is simply charged to its address alone.
     */
    static final int MAX_BUFFERED_BODY_BYTES = 4096;

    private static final String EMAIL_FIELD = "email";

    /** The longest address RFC 5321 permits, so a padded value cannot become an oversized key. */
    private static final int MAX_ACCOUNT_KEY_LENGTH = 320;

    private final AuthRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(
            AuthRateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper
    ) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = path(request);
        Optional<AuthRateLimitRule> matched = AuthRateLimitRule.forPath(path);
        if (matched.isEmpty() || "OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthRateLimitRule rule = matched.get();
        AuthRateLimitDecision byIp = rateLimiter.tryConsume(
                rule, AuthRateLimitDimension.IP, clientIpResolver.resolve(request));
        if (!byIp.allowed()) {
            reject(request, path, response, rule, AuthRateLimitDimension.IP, byIp);
            return;
        }

        // Reading the body replaces the request for everything downstream, so the wrapper has to be
        // what the rest of the chain sees.
        HttpServletRequest downstream = request;
        String account = null;

        if (AuthRateLimitRule.RESEND_PATH.equals(path)) {
            account = request.getParameter(EMAIL_FIELD);
        } else if (isJson(request)) {
            ServletInputStream body = request.getInputStream();
            byte[] prefix = readPrefix(body);
            downstream = new BufferedBodyRequestWrapper(request, prefix, body);
            account = readEmail(prefix);
        }

        String accountKey = normaliseAccount(account);
        if (accountKey != null) {
            AuthRateLimitDecision byAccount = rateLimiter.tryConsume(
                    rule, AuthRateLimitDimension.ACCOUNT, accountKey);
            if (!byAccount.allowed()) {
                reject(request, path, response, rule, AuthRateLimitDimension.ACCOUNT, byAccount);
                return;
            }
        }

        filterChain.doFilter(downstream, response);
    }

    private void reject(
            HttpServletRequest request,
            String path,
            HttpServletResponse response,
            AuthRateLimitRule rule,
            AuthRateLimitDimension dimension,
            AuthRateLimitDecision decision
    ) throws IOException {

        // The key itself is caller-supplied and stays out of the log line: an attacker choosing what
        // gets written into it is how log injection starts, and the dimension is the part that says
        // which limit fired.
        log.warn("Rate limit hit: {} {} on {} limit, retry in {}s",
                request.getMethod(), path, rule + "/" + dimension, decision.retryAfterSeconds());

        response.setStatus(ExceptionIdentifier.TOO_MANY_REQUESTS.getStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                ExceptionIdentifier.TOO_MANY_REQUESTS.name(),
                ExceptionIdentifier.TOO_MANY_REQUESTS.getDefaultMessage()));
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri != null && contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    private static byte[] readPrefix(InputStream body) throws IOException {
        byte[] buffer = new byte[MAX_BUFFERED_BODY_BYTES];
        int read = 0;
        while (read < buffer.length) {
            int count = body.read(buffer, read, buffer.length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return read == buffer.length ? buffer : Arrays.copyOf(buffer, read);
    }

    /**
     * A body too large to fit the prefix arrives here truncated and fails to parse, which costs the
     * request its account bucket but never its address one.
     */
    private String readEmail(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode email = root.get(EMAIL_FIELD);
            return email != null && email.isTextual() ? email.asText() : null;
        } catch (IOException e) {
            log.debug("Could not read the target account from the request body: {}", e.getMessage());
            return null;
        }
    }

    /** Lower-cased so rotating the capitalisation of an address does not buy a fresh bucket. */
    private static String normaliseAccount(String account) {
        if (account == null) {
            return null;
        }
        String trimmed = account.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        return key.length() > MAX_ACCOUNT_KEY_LENGTH ? key.substring(0, MAX_ACCOUNT_KEY_LENGTH) : key;
    }
}
