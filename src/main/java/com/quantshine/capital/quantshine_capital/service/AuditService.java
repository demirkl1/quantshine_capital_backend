package com.quantshine.capital.quantshine_capital.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Güvenlik/iz denetimi (audit) loglaması. Olaylar "AUDIT" adlı logger'a yazılır,
 * böylece logback'te ayrı bir appender'a yönlendirilebilir veya kolayca grep'lenir.
 * Kişisel veri (e-posta) maskelenir.
 */
@Service
public class AuditService {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /** action + (otomatik) aktör + serbest detay. */
    public void log(String action, String detail) {
        audit.info("action={} actor={} {}", action, currentActor(), detail == null ? "" : detail);
    }

    private String currentActor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null || "anonymousUser".equals(a.getName())) {
            return "anonymous";
        }
        return a.getName(); // keycloak sub
    }

    /** E-postayı kısmen maskele: a***z@domain (kişisel veri sızıntısını azaltır). */
    public static String maskEmail(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String user = email.substring(0, at);
        String masked = user.length() <= 2
                ? "**"
                : user.charAt(0) + "***" + user.charAt(user.length() - 1);
        return masked + email.substring(at);
    }
}
