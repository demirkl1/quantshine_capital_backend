package com.quantshine.capital.quantshine_capital.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Olay bildirim e-postaları (best-effort). Gönderim başarısız olursa yalnızca
 * loglanır — kayıt/onay akışını ASLA bozmaz.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JavaMailSender mailSender;

    @Value("${contact.from}")
    private String from;

    @Value("${contact.to}")
    private String adminEmail;

    /** Yeni kayıt isteği geldiğinde admin'e bilgi. */
    public void notifyAdminNewRegistration(String name, String email, String role) {
        send(adminEmail,
                "Yeni kayıt onay bekliyor — " + name,
                "Yeni bir kayıt isteği var:\n\n"
                        + "Ad: " + name + "\n"
                        + "E-posta: " + email + "\n"
                        + "Rol: " + role + "\n\n"
                        + "Admin panelden onaylayabilir veya reddedebilirsiniz.");
    }

    /** Kayıt onaylandığında kullanıcıya bilgi. */
    public void notifyUserApproved(String email, String name) {
        send(email,
                "QuantShine — Hesabınız aktifleştirildi",
                "Merhaba " + name + ",\n\n"
                        + "Hesabınız onaylandı. Artık e-posta adresiniz ve şifrenizle giriş yapabilirsiniz.\n\n"
                        + "QuantShine Capital");
    }

    private void send(String to, String subject, String text) {
        if (from == null || from.isBlank()) {
            log.debug("Mail yapılandırılmamış; bildirim atlandı: {}", subject);
            return;
        }
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setTo(to);
            m.setFrom(from);
            m.setSubject(subject);
            m.setText(text);
            mailSender.send(m);
        } catch (Exception e) {
            log.warn("Bildirim e-postası gönderilemedi ({}): {}", subject, e.getMessage());
        }
    }
}
