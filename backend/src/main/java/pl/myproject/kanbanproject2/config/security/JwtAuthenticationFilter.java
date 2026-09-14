package pl.myproject.kanbanproject2.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver handlerExceptionResolver;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserDetailsService userDetailsService,
            HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();

        if (shouldSkipFilter(requestPath, method)) {
            log.debug("JWT Filter - skipping {} {}", method, requestPath);
            filterChain.doFilter(request, response);
            return;
        }

        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String jwt = authHeader.substring(7);
            final String userEmail = jwtService.extractUsername(jwt);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (userEmail != null && authentication == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }

            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            log.warn("JWT filter error for {} {}: {}", method, requestPath, exception.getMessage());
            handlerExceptionResolver.resolveException(request, response, null, exception);
        }
    }

    /*
     * Delegates to the same patterns the filter chain permits with. The hand-rolled version this
     * replaces had drifted twice over: it still tested `/auth/`, which moved to `/api/auth/` when
     * the prefix landed, and it skipped anything merely *ending* in a static-asset extension. A
     * label is a free-text path segment, so `PUT /api/tasks/5/label/build.js` skipped the filter,
     * arrived unauthenticated and was rejected by the authorize rules with a bare 403.
     *
     * The health endpoint is the one place that conflation is wrong, and it cost this application
     * every signal it ever put there.
     *
     * "Public" answers whether a caller *must* present a token. It is a different question from
     * whether this filter should *look* at one a caller chose to present. For every other public
     * path the two coincide: nothing behind `/api/auth/login` or a bundle asset reads the principal,
     * so establishing one is wasted work. `/actuator/health` is the exception, because
     * `management.endpoint.health.show-details=when_authorized` reads exactly that principal to
     * decide whether to answer with details.
     *
     * Skipping it made `when_authorized` behave as `never`, on every deployment, since the setting
     * was written. Measured against dev: a token that answers 200 on `/api/columns` and
     * `/api/auth/devices` is ignored here, and the response is the bare
     * `{"groups":[...],"status":"UP"}` an anonymous caller gets. So the `mail` indicator PR 56 added
     * at rev 14 - the replacement signal for dead letters that nothing else reported - has never
     * been readable by anybody, and neither were the delivery-report counts added a revision ago to
     * answer a different invisible failure. **A signal nobody can read is the failure it was built
     * for, one level up**, which is now the fifth instance of that shape in this application.
     *
     * The probes are unaffected and that is the thing to check before touching this. Every
     * container probe addresses `/actuator/health/readiness` or `/actuator/health/liveness` with no
     * Authorization header at all, so they take the `authHeader == null` branch immediately below
     * and never reach token parsing. What changes is only the caller who brought a token.
     */
    private boolean shouldSkipFilter(String requestPath, String method) {
        if ("OPTIONS".equals(method)) {
            return true;
        }
        return PublicPaths.isPublic(requestPath) && !wantsAnIdentityWhenOffered(requestPath);
    }

    /**
     * Public paths that still read the principal when a caller presents one.
     *
     * <p>Deliberately the actuator endpoints and nothing else. Extending this to every public path
     * would mean a stale token on {@code /api/auth/login} - which is exactly where a stale token
     * turns up - being parsed, failing, and answering 401 to somebody trying to sign in again.
     */
    private boolean wantsAnIdentityWhenOffered(String requestPath) {
        return requestPath.startsWith("/actuator/");
    }
}
