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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import pl.myproject.kanbanproject2.config.AllowedOriginsProperties;
import pl.myproject.kanbanproject2.config.SpaRoutes;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitFilter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthRateLimitProperties.class, AllowedOriginsProperties.class})
public class SecurityConfiguration {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitProperties authRateLimitProperties;
    private final AuthRateLimiter authRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final AllowedOriginsProperties allowedOrigins;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationProvider authenticationProvider,
            AuthRateLimitProperties authRateLimitProperties,
            AuthRateLimiter authRateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper,
            AllowedOriginsProperties allowedOrigins
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitProperties = authRateLimitProperties;
        this.authRateLimiter = authRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                /*
                 * Spring Security already sends nosniff, X-Frame-Options and a no-store
                 * Cache-Control by default, and the ZAP baseline sweep does not flag any of them.
                 * What it does flag - and what has been true since the first revision of this
                 * application - is that there is no Content-Security-Policy at all. On a monolith
                 * that serves its own bundle, its API and its users' uploads from one origin, that
                 * is the header worth having: everything an injected script could reach is
                 * same-origin with the token that reaches it.
                 *
                 * Cross-Origin-Embedder-Policy is deliberately *not* set, and it is the one ZAP
                 * finding here that is declined rather than fixed. `require-corp` buys cross-origin
                 * isolation, which is worth having if you use SharedArrayBuffer or high-resolution
                 * timers; this application uses neither, and it would break the reCAPTCHA frame,
                 * which is served without a CORP header of its own. Turning on a control that
                 * breaks sign-in to satisfy a Low-severity line in a scan report is the wrong
                 * trade, and writing that down is the alternative to re-deciding it every sweep.
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
                )
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/").permitAll()
                        // The shell only; every route behind it reads its data from /api/**.
                        .requestMatchers(SpaRoutes.ALL).permitAll()
                        // Shared with JwtAuthenticationFilter so the two lists cannot drift apart.
                        .requestMatchers(PublicPaths.AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.INFRA_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.DOCS_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.WEBHOOK_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.STATIC_ASSETS).permitAll()
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
