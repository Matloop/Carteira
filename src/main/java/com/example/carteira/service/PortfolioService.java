// Em service/PortfolioService.java
package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PortfolioService {

    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;
    private final FixedIncomeService fixedIncomeService;

    public PortfolioService(TransactionRepository transactionRepository,
                            MarketDataService marketDataService,
                            FixedIncomeService fixedIncomeService) {
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
    }

    /**
     * Ponto de entrada principal para o dashboard. Orquestra a busca e a montagem de todos os dados.
     */
    public PortfolioDashboardDto getPortfolioDashboardData() {
        // 1. Obtém a lista consolidada de todas as posições de ativos com seus valores atuais.
        List<AssetPositionDto> allAssets = getConsolidatedPortfolio();

        // 2. Calcula os dados do resumo (patrimônio total, etc.).
        BigDecimal totalHeritage = allAssets.stream()
                .map(AssetPositionDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInvested = allAssets.stream()
                .map(AssetPositionDto::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal profitability = BigDecimal.ZERO;
        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            profitability = totalHeritage.subtract(totalInvested)
                    .divide(totalInvested, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability);

        // 3. Calcula a árvore de porcentagens para o gráfico de pizza.
        Map<String, AllocationNodeDto> percentages = buildAllocationTree(allAssets, totalHeritage);

        // 4. Constrói a estrutura hierárquica de ativos para a lista "Meus Ativos".
        Map<String, List<AssetSubCategoryDto>> assetsGrouped = buildAssetHierarchy(allAssets, totalHeritage);

        // 5. Retorna o DTO completo com todas as seções.
        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    /**
     * Consolida todas as transações e posições de renda fixa em uma única lista de posições de ativos.
     */
    private List<AssetPositionDto> getConsolidatedPortfolio() {
        // Agrupa transações de ativos variáveis (Ações, ETFs, Cripto)
        Map<AssetKey, List<Transaction>> groupedTransactions = transactionRepository.findAll().stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        // Calcula a posição atual para cada ativo variável
        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().stream()
                .map(entry -> calculateCurrentPosition(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull);

        // Obtém a posição atual dos ativos de renda fixa
        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositions().stream();

        // Une os dois tipos de ativos em uma lista final
        return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream).collect(Collectors.toList());
    }

    /**
     * Calcula a posição atual de um único ativo (não Renda Fixa) a partir de suas transações.
     */
    private AssetPositionDto calculateCurrentPosition(AssetKey key, List<Transaction> transactions) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                totalCost = totalCost.add(t.getQuantity().multiply(t.getPricePerUnit()));
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
            } else {
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }

        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) return null;

        BigDecimal averagePrice = totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);
        BigDecimal currentPrice = marketDataService.getPrice(key.ticker());
        BigDecimal currentValue = currentPrice.multiply(totalQuantity);
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);
        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) > 0 ?
                profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                BigDecimal.ZERO;

        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(key.ticker());
        position.setAssetType(key.assetType());
        position.setMarket(key.market());
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setCurrentPrice(currentPrice);
        position.setTotalInvested(currentPositionCost);
        position.setCurrentValue(currentValue);
        position.setProfitOrLoss(profitOrLoss);
        position.setProfitability(profitability);
        return position;
    }

    // --- MÉTODOS DE AGRUPAMENTO PARA A UI ---

    private Map<String, List<AssetSubCategoryDto>> buildAssetHierarchy(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        Map<String, Map<String, List<AssetPositionDto>>> groupedMap = allAssets.stream()
                .collect(Collectors.groupingBy(
                        asset -> {
                            if (asset.getAssetType() == AssetType.CRYPTO) return "Cripto";
                            if (asset.getMarket() == Market.US) return "EUA";
                            return "Brasil";
                        },
                        Collectors.groupingBy(asset -> getFriendlyAssetTypeName(asset.getAssetType()))
                ));

        Map<String, List<AssetSubCategoryDto>> finalResult = new HashMap<>();
        groupedMap.forEach((categoryName, subCategoryMap) -> {
            List<AssetSubCategoryDto> subCategoryList = subCategoryMap.entrySet().stream()
                    .map(entry -> {
                        String subCategoryName = entry.getKey();
                        List<AssetPositionDto> assetsInSubCategory = entry.getValue();
                        BigDecimal totalValue = assetsInSubCategory.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                        List<AssetTableRowDto> assetTableRows = assetsInSubCategory.stream()
                                .map(asset -> {
                                    BigDecimal portfolioPercentage = totalHeritage.compareTo(BigDecimal.ZERO) > 0 ?
                                            asset.getCurrentValue().divide(totalHeritage, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                                            BigDecimal.ZERO;
                                    return new AssetTableRowDto(
                                            asset.getTicker(), asset.getName(), asset.getTotalQuantity(), asset.getAveragePrice(),
                                            asset.getCurrentPrice(), asset.getCurrentValue(), asset.getProfitability(),
                                            portfolioPercentage
                                    );
                                })
                                .sorted(Comparator.comparing(AssetTableRowDto::getCurrentValue).reversed())
                                .collect(Collectors.toList());
                        return new AssetSubCategoryDto(subCategoryName, totalValue, assetTableRows);
                    })
                    .sorted(Comparator.comparing(AssetSubCategoryDto::getTotalValue).reversed())
                    .collect(Collectors.toList());
            finalResult.put(categoryName, subCategoryList);
        });
        return finalResult;
    }

    private Map<String, AllocationNodeDto> buildAllocationTree(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) return Map.of();

        Map<String, List<AssetPositionDto>> byCategory = allAssets.stream()
                .collect(Collectors.groupingBy(asset -> {
                    if (asset.getAssetType() == AssetType.CRYPTO) return "crypto";
                    if (asset.getMarket() == Market.US) return "usa";
                    return "brazil";
                }));

        return byCategory.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    BigDecimal categoryTotal = entry.getValue().stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal categoryPercentage = categoryTotal.divide(totalHeritage, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    Map<String, AllocationNodeDto> children = buildChildrenForCategory(entry.getKey(), entry.getValue(), categoryTotal);
                    return new AllocationNodeDto(categoryPercentage, children);
                }
        ));
    }

    private Map<String, AllocationNodeDto> buildChildrenForCategory(String category, List<AssetPositionDto> assets, BigDecimal categoryTotal) {
        if ("crypto".equals(category)) {
            return assets.stream().collect(Collectors.toMap(
                    AssetPositionDto::getTicker,
                    asset -> new AllocationNodeDto(asset.getCurrentValue().divide(categoryTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
            ));
        }

        Map<String, List<AssetPositionDto>> byAssetType = assets.stream().collect(Collectors.groupingBy(asset -> asset.getAssetType().name().toLowerCase()));
        return byAssetType.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    BigDecimal assetTypeTotal = entry.getValue().stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal assetTypePercentage = assetTypeTotal.divide(categoryTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    Map<String, AllocationNodeDto> grandchildren = entry.getValue().stream().collect(Collectors.toMap(
                            AssetPositionDto::getTicker,
                            asset -> new AllocationNodeDto(asset.getCurrentValue().divide(assetTypeTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
                    ));
                    return new AllocationNodeDto(assetTypePercentage, grandchildren);
                }
        ));
    }

    public PortfolioEvolutionDto getPortfolioEvolutionData() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 12; i++) {
            dates.add(today.minusMonths(i).withDayOfMonth(1));
        }
        Collections.reverse(dates); // Ordena do mais antigo para o mais recente
        dates.add(today);

        List<Transaction> allTransactions = transactionRepository.findAll();

        // 3. Itera sobre cada data e calcula o estado da carteira naquele ponto no tempo
        List<PortfolioEvolutionPointDto> evolutionPoints = dates.stream().map(date -> {
            // Filtra transações que ocorreram ATÉ a data do snapshot
            List<Transaction> transactionsUpToDate = allTransactions.stream()
                    .filter(t -> !t.getTransactionDate().isAfter(date))
                    .collect(Collectors.toList());

            List<AssetPositionDto> historicalPositions = getHistoricalPortfolio(transactionsUpToDate, date);

            BigDecimal patrimonio = historicalPositions.stream()
                    .map(AssetPositionDto::getCurrentValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal valorAplicado = historicalPositions.stream()
                    .map(AssetPositionDto::getTotalInvested)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return new PortfolioEvolutionPointDto(
                    date.format(DateTimeFormatter.ofPattern("MM/yy")), // Formato "09/25"
                    patrimonio,
                    valorAplicado
            );
        }).collect(Collectors.toList());

        return new PortfolioEvolutionDto(evolutionPoints);
    }

    private List<AssetPositionDto> getHistoricalPortfolio(List<Transaction> transactions, LocalDate calculationDate) {
        Map<AssetKey, List<Transaction>> groupedTransactions = transactions.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        // Usa parallelStream para acelerar a busca de múltiplos preços históricos
        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().parallelStream()
                .map(entry -> calculateHistoricalPosition(entry.getKey(), entry.getValue(), calculationDate))
                .filter(Objects::nonNull);

        // TODO: Implementar a busca histórica para renda fixa
        // Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositionsForDate(calculationDate).stream();
        // return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream).collect(Collectors.toList());

        return transactionalAssetsStream.collect(Collectors.toList());
    }

    private AssetPositionDto calculateHistoricalPosition(AssetKey key, List<Transaction> transactions, LocalDate calculationDate) {
        // 1. Calcula quantidade e custo médio (lógica idêntica ao cálculo atual)
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                totalCost = totalCost.add(t.getQuantity().multiply(t.getPricePerUnit()));
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
            } else {
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }
        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal averagePrice = totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);

        List<MarketDataProvider> providers = marketDataService.findProvidersFor(key.assetType());
        if (providers.isEmpty()) {
            return null;
        }
        MarketDataProvider provider = providers.get(0);

        // .block() é usado aqui pois estamos em um stream paralelo.
        PriceData historicalPriceData = provider
                .fetchHistoricalPrice(new AssetToFetch(key.ticker(), key.market()), calculationDate)
                .block();

        if (historicalPriceData == null) {
            return null; // Não podemos calcular a posição sem o preço
        }

        // 3. Monta o DTO com os dados históricos
        BigDecimal historicalPrice = historicalPriceData.price();
        BigDecimal currentValue = historicalPrice.multiply(totalQuantity);
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);
        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) > 0 ?
                profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                BigDecimal.ZERO;

        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(key.ticker());
        position.setAssetType(key.assetType());
        position.setMarket(key.market());
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setCurrentPrice(historicalPrice); // O "preço atual" neste contexto é o preço histórico
        position.setTotalInvested(currentPositionCost);
        position.setCurrentValue(currentValue);
        position.setProfitOrLoss(profitOrLoss);
        position.setProfitability(profitability);
        return position;
    }

        private String getFriendlyAssetTypeName(AssetType assetType) {
        return switch (assetType) {
            case STOCK -> "Ações";
            case ETF -> "ETFs";
            case CRYPTO -> "Criptomoedas";
            case FIXED_INCOME -> "Renda Fixa";
            default -> assetType.name();
        };
    }

    private record AssetKey(String ticker, AssetType assetType, Market market) {}
}