package com.vyoog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;

/**
 * Keycloak is reached as a plain OIDC provider. There is no Keycloak adapter: the
 * adapters are deprecated and Spring's resource server support is sufficient.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;

    @Value("${vyoog.cors-allowed-origins:https://devops.evyoog.com}")
    private String allowedOrigins;

    @Value("${vyoog.cors-allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;
    @Value("${vyoog.jwt.audience:}")
    private String requiredAudience;

    public SecurityConfig(JwtAuthConverter jwtAuthConverter) {
        this.jwtAuthConverter = jwtAuthConverter;
    }
    @Bean
    public JwtDecoder jwtDecoder() {
        var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        if (requiredAudience == null || requiredAudience.isBlank()) {
            decoder.setJwtValidator(withIssuer);
        } else {
            OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains(requiredAudience));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
        }
        return decoder;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())            // stateless bearer tokens
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // VYB-0048b, found while testing the new /auth/login endpoint, not
                // specific to it: any exception Spring MVC resolves via its own
                // default handling (rather than a @ExceptionHandler in
                // ApiExceptionHandler) triggers Boot's ErrorPageFilter to forward the
                // request to "/error" so BasicErrorController can render a body. That
                // forward re-enters this same filter chain as a fresh, unauthenticated
                // dispatch — and since "/error" matched no permitAll rule, it was
                // itself rejected 401, silently overwriting whatever real status
                // (400, 500, …) the original exception should have produced. This hit
                // every @Valid-validated endpoint in the app, not just the new one —
                // MethodArgumentNotValidException has never had an explicit handler
                // here. Permitting "/error" itself is the standard, root-cause fix;
                // ApiExceptionHandler.onValidationFailed below additionally gives
                // @Valid failures the same RFC 9457 shape as everything else, so this
                // one case doesn't even need to reach "/error" to look right.
                .requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                // VYB-0741: an external system sending a webhook has no eVyoog bearer
                // token at all — it authenticates by HMAC signature, verified inside
                // WebhookController itself, which is exactly why this is the one API
                // path carved out of "authenticated" rather than the rule everything
                // else follows.
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                // VYB-0048b: obviously carved out — this is how a bearer token is
                // obtained in the first place, so it can't itself require one.
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Backend-to-backend only, guarded by its own shared-secret header
                // check inside InternalSsoController, not by a JWT — the calling
                // app has no eVyoog-issued bearer token of its own to present here.
                .requestMatchers("/api/v1/internal/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList());
        cfg.setAllowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("X-Request-Id"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
