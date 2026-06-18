package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.repository.UserRepository;
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
 * Kayıt sırasında e-posta doğrulama: e-postaya 6 haneli kod gönderir, kayıt
 * tamamlanırken doğrulanır. Sahte/erişilemez e-posta ile kaydı engeller.
 * Şifre sıfırlamadaki ({@link PasswordResetService}) yaklaşımla aynı; izole tutuldu.
 */
@Service
@RequiredArgsConstructor
public class RegistrationVerificationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationVerificationService.class);

    private final JavaMailSender mailSender;
    private final UserRepository userRepository;

    @Value("${contact.from}")
    private String from;

    private static final long TTL_MS = 15 * 60 * 1000L;        // kod geçerlilik: 15 dk
    private static final long RESEND_COOLDOWN_MS = 60 * 1000L;  // yeniden gönderim: 60 sn
    private static final int MAX_ATTEMPTS = 5;

    private static final class Entry {
        final String code; final long expiresAt; final long createdAt; int attempts;
        Entry(String code, long expiresAt, long createdAt) {
            this.code = code; this.expiresAt = expiresAt; this.createdAt = createdAt;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    private static String norm(String email) {
        return email == null ? "" : email.toLowerCase().trim();
    }

    /** Kod üret + gönder. E-posta zaten kayıtlıysa SESSİZCE çık (kod gönderme). */
    public void sendCode(String email) {
        String key = norm(email);
        if (key.isEmpty()) return;
        long now = System.currentTimeMillis();

        Entry existing = store.get(key);
        if (existing != null && now - existing.createdAt < RESEND_COOLDOWN_MS) {
            return;
        }
        if (userRepository.findByEmail(key).isPresent()) {
            return; // zaten kayıtlı — kod gönderme (enumeration'ı da sınırlar)
        }

        String code = String.format("%06d", rng.nextInt(1_000_000));
        store.put(key, new Entry(code, now + TTL_MS, now));
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(key);
            if (from != null && !from.isBlank()) msg.setFrom(from);
            msg.setSubject("QuantShine — Kayıt Doğrulama Kodu");
            msg.setText("Kayıt doğrulama kodunuz: " + code + "\n\n"
                    + "Bu kod 15 dakika geçerlidir. Bu isteği siz yapmadıysanız yok sayın.\n\n"
                    + "QuantShine Capital");
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Kayıt doğrulama kodu gönderilemedi ({}): {}", AuditService.maskEmail(key), e.getMessage());
            store.remove(key);
        }
    }

    /** Kodu doğrula (tüketmez). */
    public synchronized boolean verify(String email, String code) {
        String key = norm(email);
        if (code == null) return false;
        Entry e = store.get(key);
        if (e == null) return false;
        if (System.currentTimeMillis() > e.expiresAt) { store.remove(key); return false; }
        if (e.attempts >= MAX_ATTEMPTS) { store.remove(key); return false; }
        if (!e.code.equals(code)) { e.attempts++; return false; }
        return true;
    }

    /** Başarılı kayıttan sonra kodu tüket. */
    public void consume(String email) {
        store.remove(norm(email));
    }
}
