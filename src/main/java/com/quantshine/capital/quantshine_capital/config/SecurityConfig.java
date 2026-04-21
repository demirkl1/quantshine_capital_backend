package com.quantshine.capital.quantshine_capital.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())

                // ── Güvenlik Header'ları ───────────────────────────────────
                .headers(headers -> headers
                        // Clickjacking önleme
                        .frameOptions(frame -> frame.deny())
                        // MIME-type sniffing önleme
                        .contentTypeOptions(Customizer.withDefaults())
                        // Referrer bilgisi sızıntısını sınırla
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )

                // ── Yetkilendirme Kuralları ────────────────────────────────
                .authorizeHttpRequests(auth -> auth

                        // Kimlik doğrulama endpoint'leri
                        .requestMatchers("/api/auth/**").permitAll()

                        // Piyasa verileri (herkese açık)
                        .requestMatchers("/api/market/**").permitAll()

                        // Haber akışı (herkese açık)
                        .requestMatchers("/api/news/**").permitAll()

                        // ── Fon endpoint'leri ──────────────────────────────
                        // Genel liste ve detay: giriş yapmadan erişilebilir
                        //   GET /api/funds          → tüm fonlar
                        //   GET /api/funds/{code}   → fon detayı (performans + dağılım)
                        //   GET /api/funds/{code}/history → grafik verisi
                        // cashBalance ve portföy detayları bu DTO'larda yer almaz.
                        .requestMatchers(HttpMethod.GET, "/api/funds", "/api/funds/*/history").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/funds/*").permitAll()

                        // Admin yönetim alanları
                        .requestMatchers("/api/admin/**", "/api/users/pending").hasRole("ADMIN")

                        // Diğer tüm istekler: oturum açmış kullanıcı gerekir
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000", "https://quant-shine.com", "https://www.quant-shine.com"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
