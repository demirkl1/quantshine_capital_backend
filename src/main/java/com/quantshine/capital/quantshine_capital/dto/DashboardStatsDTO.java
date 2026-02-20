package com.quantshine.capital.quantshine_capital.dto; // Kendi paket yoluna göre düzenle

import lombok.AllArgsConstructor; // @AllArgsConstructor için
import lombok.Data;              // @Data için
import java.math.BigDecimal;      // BigDecimal hatasını çözmek için

/**
 * Frontend (AdminAnasayfa) kartlarını besleyecek veri paketi.
 */
@Data // Getter, Setter ve ToString'i otomatik oluşturur
@AllArgsConstructor // Tüm alanları içeren bir constructor oluşturur
public class DashboardStatsDTO {
    private BigDecimal sirketFonBuyuklugu; // Toplam yatırılan net TL
    private BigDecimal sirketKarZararTl;     // Toplam Kâr/Zarar
    private BigDecimal fonBuyuklugu;       // Admin'in sorumlu olduğu fonun büyüklüğü
    private BigDecimal fonKarZararTl;        // Sorumlu olunan fonun kâr/zararı
}