package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.dto.FundPortfolioDTO;
import com.quantshine.capital.quantshine_capital.dto.StockHoldingDTO;
import com.quantshine.capital.quantshine_capital.entity.Fund;
import com.quantshine.capital.quantshine_capital.entity.FundStockHolding;
import com.quantshine.capital.quantshine_capital.entity.Stock;
import com.quantshine.capital.quantshine_capital.entity.StockTradeHistory;
import com.quantshine.capital.quantshine_capital.entity.Investment;
import com.quantshine.capital.quantshine_capital.repository.FundRepository;
import com.quantshine.capital.quantshine_capital.repository.FundCommodityHoldingRepository;
import com.quantshine.capital.quantshine_capital.repository.FundStockHoldingRepository;
import com.quantshine.capital.quantshine_capital.repository.InvestmentRepository;
import com.quantshine.capital.quantshine_capital.repository.StockRepository;
import com.quantshine.capital.quantshine_capital.repository.StockTradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockRepository stockRepository;
    private final FundStockHoldingRepository fundStockHoldingRepository;
    private final FundCommodityHoldingRepository fundCommodityHoldingRepository;
    private final FundRepository fundRepository;
    private final StockTradeHistoryRepository stockTradeHistoryRepository;
    private final InvestmentRepository investmentRepository;

    public List<Stock> getAllStocks() {
        return stockRepository.findAll();
    }

    @Transactional
    public Stock updateStockPrice(String stockCode, String stockName, BigDecimal currentPrice,
                                  BigDecimal change, String changePercent) {

        Stock stock = stockRepository.findByStockCode(stockCode.toUpperCase())
                .orElse(new Stock());

        // Önceki fiyatı kaydet
        if (stock.getCurrentPrice() != null) {
            stock.setPreviousClose(stock.getCurrentPrice());
        }

        // Yeni değerleri set et
        stock.setStockCode(stockCode.toUpperCase());
        stock.setStockName(stockName != null ? stockName : stockCode);
        stock.setCurrentPrice(currentPrice);
        stock.setChange(change);
        stock.setChangePercent(changePercent);
        stock.setLastUpdate(LocalDateTime.now());

        Stock saved = stockRepository.save(stock);

        log.debug("DB'ye kaydedildi: {} = {} TL", saved.getStockCode(), saved.getCurrentPrice());

        return saved;
    }

    /**
     * fund.cashBalance = 0 iken yatırımcı parası varsa otomatik başlatır.
     * Bu sayede mevcut fonlar da hisse alım/satımına hazır hale gelir.
     */
    @Transactional
    public BigDecimal initCashBalanceIfNeeded(Fund fund, BigDecimal currentStocksValue) {
        BigDecimal cash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
        if (cash.compareTo(BigDecimal.ZERO) > 0) return cash;

        // cashBalance sıfır: toplam yatırım tutarından hisselerin MALİYETİNİ düş
        List<Investment> investments = investmentRepository.findByFundCode(fund.getFundCode());

        // inv.balance = yatırımcının ödediği gerçek tutar (lot × currentPrice değil!)
        BigDecimal totalInvested = investments.stream()
                .map(inv -> inv.getBalance() != null ? inv.getBalance() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            // Hisse maliyet değeri = avgCost × lot (piyasa değeri değil, alış fiyatı)
            BigDecimal stocksCostValue = fundStockHoldingRepository
                    .findByFundCode(fund.getFundCode().toUpperCase())
                    .stream()
                    .map(h -> h.getAvgCost().multiply(h.getLotCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal commoditiesCost = fundCommodityHoldingRepository
                    .findByFundCode(fund.getFundCode().toUpperCase()).stream()
                    .map(h -> h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal derived = totalInvested.subtract(stocksCostValue).subtract(commoditiesCost).max(BigDecimal.ZERO);
            fund.setCashBalance(derived);
            fundRepository.save(fund);
            return derived;
        }
        return BigDecimal.ZERO;
    }

    // Fon portföyünü getir
    @Transactional
    public FundPortfolioDTO getFundPortfolio(String fundCode) {
        List<FundStockHolding> holdings = fundStockHoldingRepository.findByFundCode(fundCode.toUpperCase());

        FundPortfolioDTO portfolio = new FundPortfolioDTO();
        portfolio.setFundCode(fundCode.toUpperCase());

        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı: " + fundCode));

        BigDecimal totalStocksValue = BigDecimal.ZERO;
        BigDecimal totalCostValue = BigDecimal.ZERO;
        List<StockHoldingDTO> holdingDTOs = new ArrayList<>();

        for (FundStockHolding holding : holdings) {
            StockHoldingDTO dto = new StockHoldingDTO();
            dto.setStockCode(holding.getStock().getStockCode());
            dto.setStockName(holding.getStock().getStockName());
            dto.setLot(holding.getLotCount());
            dto.setAvgCost(holding.getAvgCost());
            dto.setCurrentPrice(holding.getStock().getCurrentPrice());

            BigDecimal costValue = holding.getAvgCost().multiply(holding.getLotCount());
            BigDecimal currentValue = holding.getStock().getCurrentPrice().multiply(holding.getLotCount());

            dto.setCostValue(costValue);
            dto.setCurrentValue(currentValue);

            totalStocksValue = totalStocksValue.add(currentValue);
            totalCostValue = totalCostValue.add(costValue);

            holdingDTOs.add(dto);
        }

        // cashBalance 0 ise yatırımcı toplamından türet ve DB'ye kaydet
        BigDecimal cashBalance = initCashBalanceIfNeeded(fund, totalStocksValue);

        BigDecimal totalCommoditiesValue = fundCommodityHoldingRepository
                .findByFundCode(fundCode.toUpperCase()).stream()
                .map(h -> h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPortfolioValue = cashBalance.add(totalStocksValue).add(totalCommoditiesValue);

        portfolio.setCashBalance(cashBalance);
        portfolio.setStocksValue(totalStocksValue);
        portfolio.setTotalValue(totalPortfolioValue);
        portfolio.setTotalProfitLoss(totalStocksValue.subtract(totalCostValue));
        portfolio.setHoldings(holdingDTOs);

        return portfolio;
    }

    // Hisse alım/satım işlemi
    @Transactional
    public String executeStockTrade(String fundCode, String stockCode, BigDecimal lot,
                                    BigDecimal price, String type) {
        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı: " + fundCode));

        Stock stock = stockRepository.findByStockCode(stockCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Hisse bulunamadı: " + stockCode));

        BigDecimal totalAmount = price.multiply(lot);

        if ("BUY".equalsIgnoreCase(type)) {
            // Mevcut hisselerin toplam değerini al (initCashBalanceIfNeeded için gerekli)
            BigDecimal currentStocksVal = fundStockHoldingRepository.findByFundCode(fundCode.toUpperCase())
                    .stream()
                    .map(h -> h.getStock().getCurrentPrice().multiply(h.getLotCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // cashBalance 0 ise yatırımcı toplamından otomatik türet
            BigDecimal cashBalance = initCashBalanceIfNeeded(fund, currentStocksVal);

            if (cashBalance.compareTo(totalAmount) < 0) {
                throw new RuntimeException("Yetersiz bakiye! Mevcut: " + cashBalance + " TL, Gerekli: " + totalAmount + " TL");
            }

            fund.setCashBalance(cashBalance.subtract(totalAmount));
            fundRepository.save(fund);

            FundStockHolding holding = fundStockHoldingRepository
                    .findByFundCodeAndStock_StockCode(fundCode.toUpperCase(), stockCode.toUpperCase())
                    .orElse(new FundStockHolding());

            if (holding.getId() == null) {
                holding.setFundCode(fundCode.toUpperCase());
                holding.setStock(stock);
                holding.setLotCount(lot);
                holding.setAvgCost(price);
                holding.setTotalCost(totalAmount);
                holding.setPurchaseDate(LocalDateTime.now());
            } else {
                BigDecimal oldTotal = holding.getAvgCost().multiply(holding.getLotCount());
                BigDecimal newTotal = oldTotal.add(totalAmount);
                BigDecimal newLotCount = holding.getLotCount().add(lot);

                holding.setAvgCost(newTotal.divide(newLotCount, 4, RoundingMode.HALF_UP));
                holding.setLotCount(newLotCount);
                holding.setTotalCost(newTotal);
            }

            holding.setCurrentValue(holding.getLotCount().multiply(stock.getCurrentPrice()));
            fundStockHoldingRepository.save(holding);

            saveTradeHistory(fundCode, stockCode, stock.getStockName(), "BUY", lot, price, totalAmount);

            return "Alış işlemi başarılı: " + lot + " lot " + stockCode + " @ " + price + " TL";

        } else if ("SELL".equalsIgnoreCase(type)) {
            FundStockHolding holding = fundStockHoldingRepository
                    .findByFundCodeAndStock_StockCode(fundCode.toUpperCase(), stockCode.toUpperCase())
                    .orElseThrow(() -> new RuntimeException("Satılacak hisse bulunamadı!"));

            if (holding.getLotCount().compareTo(lot) < 0) {
                throw new RuntimeException("Yetersiz lot! Mevcut: " + holding.getLotCount() + ", Satılmak istenen: " + lot);
            }

            BigDecimal cashBalance = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
            fund.setCashBalance(cashBalance.add(totalAmount));
            fundRepository.save(fund);

            holding.setLotCount(holding.getLotCount().subtract(lot));

            if (holding.getLotCount().compareTo(BigDecimal.ZERO) == 0) {
                fundStockHoldingRepository.delete(holding);
            } else {
                holding.setCurrentValue(holding.getLotCount().multiply(stock.getCurrentPrice()));
                fundStockHoldingRepository.save(holding);
            }

            saveTradeHistory(fundCode, stockCode, stock.getStockName(), "SELL", lot, price, totalAmount);

            return "Satış işlemi başarılı: " + lot + " lot " + stockCode + " @ " + price + " TL";
        }

        throw new RuntimeException("Geçersiz işlem tipi: " + type);
    }

    private void saveTradeHistory(String fundCode, String stockCode, String stockName,
                                  String type, BigDecimal lot, BigDecimal price, BigDecimal totalAmount) {
        StockTradeHistory history = new StockTradeHistory();
        history.setFundCode(fundCode.toUpperCase());
        history.setStockCode(stockCode.toUpperCase());
        history.setStockName(stockName);
        history.setType(type);
        history.setLot(lot);
        history.setPrice(price);
        history.setTotalAmount(totalAmount);
        history.setTradeDate(LocalDateTime.now());
        stockTradeHistoryRepository.save(history);
    }

    public List<StockTradeHistory> getStockTradeHistory(String fundCode) {
        return stockTradeHistoryRepository.findByFundCodeOrderByTradeDateDesc(fundCode);
    }

    public List<StockTradeHistory> getAllStockTradeHistory() {
        return stockTradeHistoryRepository.findAllByOrderByTradeDateDesc();
    }
}