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
                .headers(headers -> headers
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives(SecurityHeaders.CONTENT_SECURITY_POLICY))
                        .permissionsPolicyHeader(permissions ->
                                permissions.policy(SecurityHeaders.PERMISSIONS_POLICY))
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .crossOriginOpenerPolicy(coop ->
                                coop.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .crossOriginResourcePolicy(corp ->
                                corp.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                // TLS ends at the ingress, so the default isSecure() matcher would never send HSTS.
                                .requestMatcher(AnyRequestMatcher.INSTANCE)
                                .maxAgeInSeconds(SecurityHeaders.STRICT_TRANSPORT_SECURITY_MAX_AGE)
                                .includeSubDomains(true)
                                .preload(false))
                )
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PublicPaths.AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.INFRA_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.DOCS_ENDPOINTS).permitAll()
                        .requestMatchers(PublicPaths.WEBHOOK_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (authRateLimitProperties.enabled()) {
            http.addFilterAfter(
                    new AuthRateLimitFilter(authRateLimiter, clientIpResolver, objectMapper),
                    CorsFilter.class);
        }

        if (apiRateLimitProperties.enabled()) {
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
