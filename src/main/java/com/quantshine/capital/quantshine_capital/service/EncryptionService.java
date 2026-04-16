package com.quantshine.capital.quantshine_capital.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

/**
 * Hassas verileri (geçici şifre gibi) AES ile şifreler/çözer.
 * Şifreleme anahtarı ortam değişkeninden gelir (ENCRYPTION_SECRET).
 */
@Service
public class EncryptionService {

    private final TextEncryptor encryptor;

    public EncryptionService(
            @Value("${encryption.secret}") String secret,
            @Value("${encryption.salt}") String salt) {
        // Encryptors.text: AES-256/CBC + PKCS5 padding, salt hex string olmalı
        this.encryptor = Encryptors.text(secret, salt);
    }

    public String encrypt(String plainText) {
        return encryptor.encrypt(plainText);
    }

    public String decrypt(String cipherText) {
        return encryptor.decrypt(cipherText);
    }
}
