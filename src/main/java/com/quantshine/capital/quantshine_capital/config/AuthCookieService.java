package com.quantshine.capital.quantshine_capital.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * BFF cookie yönetimi. Token'lar artık yanıt gövdesinde değil, JavaScript'in
 * erişemeyeceği HttpOnly cookie'lerde taşınır (XSS ile token sızdırmayı engeller).
 *
 * - {@code qs_access}  : access token. Tüm /api isteklerine gider (Path=/api), SameSite=Lax.
 * - {@code qs_refresh} : refresh token. Yalnızca /api/auth uçlarına gider (Path=/api/auth),
 *                        SameSite=Strict — CSRF yüzeyini daraltır.
 *
 * {@code Secure} bayrağı prod'da true (HTTPS); lokal http geliştirme için
 * {@code app.cookie.secure=false} ile kapatılır.
 */
@Component
public class AuthCookieService {

    public static final String ACCESS_COOKIE = "qs_access";
    public static final String REFRESH_COOKIE = "qs_refresh";

    private static final String ACCESS_PATH = "/api";
    private static final String REFRESH_PATH = "/api/auth";

    @Value("${app.cookie.secure:true}")
    private boolean secure;

    public ResponseCookie accessCookie(String token, long maxAgeSeconds) {
        return base(ACCESS_COOKIE, token, ACCESS_PATH, "Lax")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    public ResponseCookie refreshCookie(String token, long maxAgeSeconds) {
        return base(REFRESH_COOKIE, token, REFRESH_PATH, "Strict")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    public ResponseCookie clearAccessCookie() {
        return base(ACCESS_COOKIE, "", ACCESS_PATH, "Lax").maxAge(0).build();
    }

    public ResponseCookie clearRefreshCookie() {
        return base(REFRESH_COOKIE, "", REFRESH_PATH, "Strict").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path, String sameSite) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .path(path)
                .sameSite(sameSite);
    }

    /** İstekten verilen isimli cookie değerini okur (yoksa null). */
    public static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) {
                String v = c.getValue();
                return (v == null || v.isBlank()) ? null : v;
            }
        }
        return null;
    }
}
