package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
// InvestorPortfolioDTO.java
public class InvestorPortfolioDTO {
    private BigDecimal toplamLot;
    private BigDecimal karZararTl;
    private String karZararYuzde;
    private BigDecimal guncelDeger;
    private BigDecimal toplamPortfoyBuyuklugu;
    private String genelKarZararYuzde;
}