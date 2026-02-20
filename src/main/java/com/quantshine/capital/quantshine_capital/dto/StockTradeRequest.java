package com.quantshine.capital.quantshine_capital.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class StockTradeRequest {
    private String fundCode;
    private String stockCode;
    private BigDecimal lot;
    private BigDecimal price;
    private String type; // BUY veya SELL
}