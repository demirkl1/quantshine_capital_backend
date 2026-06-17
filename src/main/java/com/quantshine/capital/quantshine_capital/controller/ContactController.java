package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.dto.ContactRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * İletişim formu → e-posta. Ziyaretçinin mesajını {@code contact.to} adresine
 * gönderir; Reply-To ziyaretçinin adresine ayarlanır (doğrudan yanıtlanabilir).
 * Spam'e karşı IP başına basit throttle uygulanır.
 */
@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);

    private final JavaMailSender mailSender;

    @Value("${contact.to}")
    private String to;

    @Value("${contact.from}")
    private String from;

    // IP başına: 10 dakikalık pencerede en fazla 3 mesaj
    private static final int MAX = 3;
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<?> submit(@Valid @RequestBody ContactRequest req, HttpServletRequest request) {
        if (!allow(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Çok fazla mesaj gönderdiniz. Lütfen bir süre sonra tekrar deneyin.");
        }

        if (from == null || from.isBlank()) {
            // MAIL_USERNAME/CONTACT_FROM henüz ayarlanmadıysa gönderim yapılandırılmamıştır.
            log.warn("İletişim formu yapılandırılmamış (MAIL_USERNAME boş); mesaj gönderilemedi.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Mesaj gönderimi şu an kullanılamıyor. Lütfen e-posta ile ulaşın.");
        }

        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(to);
            mail.setFrom(from);
            mail.setReplyTo(req.getEmail());
            mail.setSubject("Yeni İletişim Mesajı — " + req.getName());
            mail.setText(
                    "Ad: " + req.getName() + "\n"
                    + "E-posta: " + req.getEmail() + "\n"
                    + "Telefon: " + (req.getPhone() == null || req.getPhone().isBlank() ? "-" : req.getPhone()) + "\n\n"
                    + "Mesaj:\n" + req.getMessage()
            );
            mailSender.send(mail);
            return ResponseEntity.ok("Mesajınız iletildi. En kısa sürede dönüş yapılacaktır.");
        } catch (Exception e) {
            log.error("İletişim e-postası gönderilemedi: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Mesaj şu an gönderilemiyor. Lütfen daha sonra tekrar deneyin.");
        }
    }

    /** IP başına kayan pencere throttle. */
    private synchronized boolean allow(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> dq = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) {
            dq.pollFirst();
        }
        if (dq.size() >= MAX) {
            return false;
        }
        dq.addLast(now);
        return true;
    }

    private String clientIp(HttpServletRequest request) {
        // nginx'in eklediği SON adres güvenilir (spoof'a karşı; AuthController ile aynı yaklaşım).
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
