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
     * Delegates to the same patterns the filter chain permits with, rather than a hand-rolled list
     * that had drifted (still testing `/auth/` after the `/api` prefix landed, and skipping any
     * path merely *ending* in a static-asset extension, which let a task label like `build.js`
     * bypass the filter entirely).
     *
     * "Public" answers whether a caller *must* present a token - a different question from whether
     * this filter should *look* at one offered voluntarily. They coincide everywhere except
     * `/actuator/health`, where `management.endpoint.health.show-details=when_authorized` reads the
     * principal to decide whether to answer with details; skipping it there made `when_authorized`
     * behave as `never` on every deployment; for example, the `mail` health indicator was never
     * readable by anybody.
     *
     * Container probes are unaffected: they carry no Authorization header, so they take the
     * `authHeader == null` branch below and never reach token parsing.
     */
    private boolean shouldSkipFilter(String requestPath, String method) {
        if ("OPTIONS".equals(method)) {
            return true;
        }
        return PublicPaths.isPublic(requestPath) && !wantsAnIdentityWhenOffered(requestPath);
    }

    /**
     * Public paths that still read the principal when a caller presents one - deliberately the
     * actuator endpoints and nothing else, or a stale token on {@code /api/auth/login} would fail
     * parsing and answer 401 to somebody trying to sign in again.
     */
    private boolean wantsAnIdentityWhenOffered(String requestPath) {
        return requestPath.startsWith("/actuator/");
    }
}
