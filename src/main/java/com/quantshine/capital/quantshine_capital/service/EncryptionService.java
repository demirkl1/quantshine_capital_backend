package com.quantshine.capital.quantshine_capital.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

/**
 * Hassas verileri (geçici şifre gibi) AES ile şifreler/çözer.
 * Şifreleme anahtarı ortam değişkeninden gelir (ENCRYPTION_SECRET).
 *
 * Şifreleme artık AES-256-GCM (authenticated; Encryptors.delux) ile yapılır —
 * CBC'nin aksine bütünlük/kurcalama koruması sağlar. Çözme tarafında geçiş dönemi
 * için önce GCM denenir, başarısız olursa eski CBC verisi (Encryptors.text) ile
 * çözülür. Böylece deploy anında DB'de bekleyen eski (CBC) şifreli kayıtlar
 * bozulmadan okunmaya devam eder; yeni kayıtlar GCM olur. Tüm eski kayıtlar
 * tükenince (onay/ret ile) cbcLegacy kaldırılabilir.
 */
@Service
public class EncryptionService {

    private final TextEncryptor gcm;        // AES-256-GCM — yeni standart (şifrele + birincil çöz)
    private final TextEncryptor cbcLegacy;  // AES-256-CBC — yalnızca eski veriyi çözmek için (geçiş)

    public EncryptionService(
            @Value("${encryption.secret}") String secret,
            @Value("${encryption.salt}") String salt) {
        this.gcm = Encryptors.delux(secret, salt);
        this.cbcLegacy = Encryptors.text(secret, salt);
    }

    public String encrypt(String plainText) {
        return gcm.encrypt(plainText);
    }

    public String decrypt(String cipherText) {
        try {
            return gcm.decrypt(cipherText);
        } catch (Exception e) {
            // Geçiş: deploy öncesi CBC ile şifrelenmiş kayıtlar
            return cbcLegacy.decrypt(cipherText);
        }
    }
}
