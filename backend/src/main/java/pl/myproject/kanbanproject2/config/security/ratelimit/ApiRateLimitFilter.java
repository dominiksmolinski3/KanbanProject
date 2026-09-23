package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.myproject.kanbanproject2.exception.ErrorResponse;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.user.User;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private final ApiRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public ApiRateLimitFilter(ApiRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Integer accountId = accountId(request);
        if (accountId == null) {
            chain.doFilter(request, response);
            return;
        }

        AuthRateLimitDecision decision = limiter.attempt(accountId);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        log.debug("API rate limit hit by account {}, retry in {}s", accountId, decision.retryAfterSeconds());
        response.setStatus(ExceptionIdentifier.TOO_MANY_REQUESTS.getStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                ExceptionIdentifier.TOO_MANY_REQUESTS.name(),
                ExceptionIdentifier.TOO_MANY_REQUESTS.getDefaultMessage()));
    }

    private static Integer accountId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(request.getContextPath() + API_PREFIX)) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }
}
