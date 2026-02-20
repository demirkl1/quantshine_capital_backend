package com.quantshine.capital.quantshine_capital.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP başına maksimum 5 başarısız giriş denemesi / 1 dakika.
 * Başarılı girişte sayaç sıfırlanır.
 * Harici dependency gerektirmez.
 */
@Service
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS   = 60_000; // 1 dakika

    private final ConcurrentHashMap<String, Deque<Long>> failedAttempts = new ConcurrentHashMap<>();

    /**
     * @return true → giriş izni verildi, false → rate limit aşıldı
     */
    public boolean isAllowed(String ip) {
        long now = System.currentTimeMillis();
        failedAttempts.compute(ip, (k, timestamps) -> {
            if (timestamps == null) return new ArrayDeque<>();
            // Pencere dışına çıkan denemeleri temizle
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }
            return timestamps;
        });
        return failedAttempts.getOrDefault(ip, new ArrayDeque<>()).size() < MAX_ATTEMPTS;
    }

    /**
     * Başarılı girişte sayacı sıfırla.
     */
    public void onSuccess(String ip) {
        failedAttempts.remove(ip);
    }

    /**
     * Başarısız girişi kaydet (AuthController çağırmak yerine otomatik yönetilir —
     * isAllowed() false dönmeden önce sayacı artır).
     */
    public void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        failedAttempts.compute(ip, (k, timestamps) -> {
            if (timestamps == null) timestamps = new ArrayDeque<>();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }
            timestamps.addLast(now);
            return timestamps;
        });
    }
}
