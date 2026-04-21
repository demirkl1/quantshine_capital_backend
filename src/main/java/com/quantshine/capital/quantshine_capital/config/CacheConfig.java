package com.quantshine.capital.quantshine_capital.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine tabanlı in-memory cache yapılandırması.
 *
 * Cache isimleri ve TTL değerleri:
 *   • "funds"           — Fon listesi: 5 dk  (düşük değişim sıklığı)
 *   • "fundDetail"      — Fon detayı: 10 dk  (varlık dağılımı hesaplamaları pahalı)
 *   • "advisorStats"    — Danışman stats: 10 dk (borsa güncellemeleri arası yeterli)
 *   • "adminStats"      — Admin stats: 5 dk   (60 sn polling, 5 dk cache yeterli)
 *   • "investorFunds"   — Yatırımcı fon listesi: 2 dk (işlem sonrası evict edilir)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Varsayılan: 5 dk TTL, maksimum 500 giriş
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .recordStats()   // Micrometer / Actuator ile izlenebilir
        );

        // Özel TTL gerektiren cache'ler için tek tek tanımlama
        manager.setCacheNames(java.util.List.of(
            "funds",
            "fundDetail",
            "advisorStats",
            "adminStats",
            "investorFunds",
            "news"
        ));

        return manager;
    }
}
