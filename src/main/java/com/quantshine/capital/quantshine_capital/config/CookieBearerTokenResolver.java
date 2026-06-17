package com.quantshine.capital.quantshine_capital.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Access token'ı önce {@code qs_access} HttpOnly cookie'sinden okur.
 * Cookie yoksa standart {@code Authorization: Bearer} header'ına düşer.
 *
 * Header fallback, ROPC→cookie geçişi sırasında eski frontend bundle'larının
 * (Bearer header gönderen) kesintisiz çalışmasını sağlar. Geçiş tamamlandığında
 * fallback kaldırılabilir.
 */
@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String fromCookie = AuthCookieService.readCookie(request, AuthCookieService.ACCESS_COOKIE);
        if (fromCookie != null) {
            return fromCookie;
        }
        return headerResolver.resolve(request);
    }
}
