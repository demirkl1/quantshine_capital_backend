package com.quantshine.capital.quantshine_capital.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Şifre sıfırlama akışı: e-postaya 6 haneli doğrulama kodu gönderir, doğrular ve
 * doğrulanınca Keycloak şifresini sıfırlar.
 *
 * Kodlar bellekte (in-memory) tutulur — kısa ömürlü (10 dk) olduğundan yeniden
 * başlatmada kaybolması kabul edilebilir (kullanıcı yeni kod ister). Enumeration'a
 * karşı: e-posta kayıtlı değilse sessizce hiçbir şey yapılmaz (controller yine 200 döner).
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final JavaMailSender mailSender;
    private final UserService userService;

    @Value("${contact.from}")
    private String from;

    private static final long TTL_MS = 10 * 60 * 1000L;       // kod geçerlilik: 10 dk
    private static final long RESEND_COOLDOWN_MS = 60 * 1000L; // yeniden gönderim: 60 sn
    private static final int MAX_ATTEMPTS = 5;                 // hatalı kod denemesi

    private static final class Entry {
        final String code;
        final long expiresAt;
        final long createdAt;
        int attempts;
        Entry(String code, long expiresAt, long createdAt) {
            this.code = code; this.expiresAt = expiresAt; this.createdAt = createdAt;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    private static String norm(String email) {
        return email == null ? "" : email.toLowerCase().trim();
    }

    /** Kod üret + e-posta gönder. E-posta kayıtlı/aktif değilse SESSİZCE çık (enumeration koruması). */
    public void requestReset(String email) {
        String key = norm(email);
        long now = System.currentTimeMillis();

        Entry existing = store.get(key);
        if (existing != null && now - existing.createdAt < RESEND_COOLDOWN_MS) {
            return; // çok sık yeniden gönderim — sessiz
        }
        if (!userService.canResetPassword(key)) {
            return; // kullanıcı yok / aktif değil — sessiz
        }

        String code = String.format("%06d", rng.nextInt(1_000_000));
        store.put(key, new Entry(code, now + TTL_MS, now));
        try {
            sendCodeEmail(key, code);
        } catch (Exception e) {
            log.error("Şifre sıfırlama kodu gönderilemedi ({}): {}", key, e.getMessage());
            store.remove(key); // gönderilemediyse kodu tutma
        }
    }

    /** Kodun geçerli olup olmadığını kontrol et (tüketmez). */
    public synchronized boolean verify(String email, String code) {
        String key = norm(email);
        Entry e = store.get(key);
        if (e == null) return false;
        if (System.currentTimeMillis() > e.expiresAt) { store.remove(key); return false; }
        if (e.attempts >= MAX_ATTEMPTS) { store.remove(key); return false; }
        if (!e.code.equals(code)) { e.attempts++; return false; }
        return true;
    }

    /** Kodu doğrula + Keycloak şifresini sıfırla + kodu tüket. */
    public void reset(String email, String code, String newPassword) {
        String key = norm(email);
        if (!verify(key, code)) {
            throw new IllegalArgumentException("Doğrulama kodu geçersiz veya süresi dolmuş.");
        }
        userService.resetPasswordByEmail(key, newPassword);
        store.remove(key); // tek kullanımlık
        log.info("Şifre sıfırlama tamamlandı: {}", key);
    }

    private void sendCodeEmail(String email, String code) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(email);
        if (from != null && !from.isBlank()) msg.setFrom(from);
        msg.setSubject("QuantShine — Şifre Sıfırlama Kodu");
        msg.setText(
                "Şifre sıfırlama doğrulama kodunuz: " + code + "\n\n"
                + "Bu kod 10 dakika geçerlidir. Bu isteği siz yapmadıysanız bu e-postayı yok sayın.\n\n"
                + "QuantShine Capital"
        );
        mailSender.send(msg);
    }
}
