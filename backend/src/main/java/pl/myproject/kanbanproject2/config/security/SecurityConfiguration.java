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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import pl.myproject.kanbanproject2.config.SpaRoutes;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitFilter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class SecurityConfiguration {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitProperties authRateLimitProperties;
    private final AuthRateLimiter authRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationProvider authenticationProvider,
            AuthRateLimitProperties authRateLimitProperties,
            AuthRateLimiter authRateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitProperties = authRateLimitProperties;
        this.authRateLimiter = authRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
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
        configuration.setAllowedOrigins(List.of(
                "https://kanbanproject.pl",
                "https://www.kanbanproject.pl",
                "http://localhost:5173",
                "http://localhost:3000",
                "http://localhost:5174",
                "http://localhost:8080",
                "http://localhost:80",
                "http://127.0.0.1:8080",
                "http://app:8080"
        ));
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
