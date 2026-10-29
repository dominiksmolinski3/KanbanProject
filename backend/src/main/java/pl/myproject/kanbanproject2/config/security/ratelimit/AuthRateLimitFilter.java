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
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import pl.myproject.kanbanproject2.exception.ErrorResponse;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final int MAX_BUFFERED_BODY_BYTES = 16 * 1024;

    static final String UNREADABLE_CODE = "MALFORMED_REQUEST";

    private static final String EMAIL_FIELD = "email";

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

        HttpServletRequest downstream = request;
        String account = null;

        if (isJson(request)) {
            ServletInputStream body = request.getInputStream();
            byte[] prefix = readPrefix(body);
            downstream = new BufferedBodyRequestWrapper(request, prefix, body);
            account = readEmail(prefix);
        }

        String accountKey = normaliseAccount(account);
        if (accountKey == null && AuthRateLimitRule.namesAnAccount(path)) {
            refuseUnreadable(request, path, response);
            return;
        }
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

    private void refuseUnreadable(HttpServletRequest request, String path, HttpServletResponse response)
            throws IOException {

        log.warn("Refused {} {}: no account could be read from the request", request.getMethod(), path);

        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(UNREADABLE_CODE, "The request body could not be read"));
    }

    // Match the decoded path Spring routes on: on the raw URI, /api/auth/%6Cogin would skip every limit.
    private static String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private static boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return "application".equals(type.getType())
                    && ("json".equals(type.getSubtype()) || "json".equals(type.getSubtypeSuffix()));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
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
