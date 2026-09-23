package pl.myproject.kanbanproject2.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import pl.myproject.kanbanproject2.config.AllowedOriginsProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimitFilter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitFilter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthRateLimitProperties.class, ApiRateLimitProperties.class, AllowedOriginsProperties.class})
public class SecurityConfiguration {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitProperties authRateLimitProperties;
    private final AuthRateLimiter authRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final AllowedOriginsProperties allowedOrigins;
    private final ApiRateLimitProperties apiRateLimitProperties;
    private final ApiRateLimiter apiRateLimiter;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationProvider authenticationProvider,
            AuthRateLimitProperties authRateLimitProperties,
            AuthRateLimiter authRateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper,
            AllowedOriginsProperties allowedOrigins,
            ApiRateLimitProperties apiRateLimitProperties,
            ApiRateLimiter apiRateLimiter
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitProperties = authRateLimitProperties;
        this.authRateLimiter = authRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
        this.apiRateLimitProperties = apiRateLimitProperties;
        this.apiRateLimiter = apiRateLimiter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                /*
                 * A Content-Security-Policy is worth having here because one origin serves the
                 * bundle, the API and every uploaded attachment, so anything an injected script
                 * could reach is same-origin with the token that reaches it.
                 *
                 * Cross-Origin-Embedder-Policy is deliberately *not* set: `require-corp` would
                 * break the reCAPTCHA frame (which sends no CORP header) for cross-origin
                 * isolation this application has no use for.
                 */
                .headers(headers -> headers
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives(SecurityHeaders.CONTENT_SECURITY_POLICY))
                        .permissionsPolicyHeader(permissions ->
                                permissions.policy(SecurityHeaders.PERMISSIONS_POLICY))
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // Nothing outside this deployment embeds its pages or its assets, so
                        // same-origin is the honest setting rather than a cautious one. It is also
                        // what ZAP flagged on the bundle files themselves, not only on the shell.
                        .crossOriginOpenerPolicy(coop ->
                                coop.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .crossOriginResourcePolicy(corp ->
                                corp.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
                        /*
                         * Spring's default HSTS writer is gated on request.isSecure(), but TLS
                         * terminates at the Container Apps ingress (`transport = "http"`), so it
                         * had never actually fired. Written unconditionally instead: a user agent
                         * ignores HSTS received over plain HTTP, so there's no case where sending
                         * it is wrong. `server.forward-headers-strategy` is declined as the fix
                         * because it would also rewrite getRemoteAddr() from X-Forwarded-For,
                         * which is what security.rate-limit.trusted-proxy-count exists to control
                         * deliberately via ClientIpResolver.
                         */
                        .httpStrictTransportSecurity(hsts -> hsts
                                .requestMatcher(AnyRequestMatcher.INSTANCE)
                                .maxAgeInSeconds(SecurityHeaders.STRICT_TRANSPORT_SECURITY_MAX_AGE)
                                .includeSubDomains(true)
                                .preload(false))
                )
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // No shell and no bundle: nginx serves both from its own container and
                        // neither reaches this chain. What is left is the API, and every entry
                        // below is public because a caller that has no token has to reach it.
                        // Shared with JwtAuthenticationFilter so the two lists cannot drift apart.
                        .requestMatchers(PublicPaths.AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.INFRA_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.DOCS_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.WEBHOOK_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (authRateLimitProperties.enabled()) {
            // Built here rather than declared as a bean on purpose: Spring Boot auto-registers any
            // Filter bean against the servlet container as well, which would run it ahead of the
            // CORS filter and strip the CORS headers off a 429.
            http.addFilterAfter(
                    new AuthRateLimitFilter(authRateLimiter, clientIpResolver, objectMapper),
                    CorsFilter.class);
        }

        if (apiRateLimitProperties.enabled()) {
            // After the JWT filter, since the account it keys on is what that filter finds; built
            // here for the same servlet-registration reason as the auth filter above.
            http.addFilterAfter(new ApiRateLimitFilter(apiRateLimiter, objectMapper), JwtAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
