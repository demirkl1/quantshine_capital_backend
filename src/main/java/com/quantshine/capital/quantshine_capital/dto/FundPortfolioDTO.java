package com.quantshine.capital.quantshine_capital.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class FundPortfolioDTO {
    private String fundCode;
    private BigDecimal totalValue;
    private BigDecimal cashBalance;
    private BigDecimal stocksValue;
    private BigDecimal totalProfitLoss;
    private List<StockHoldingDTO> holdings;
}