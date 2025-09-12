package com.example.carteira.service;

import com.example.carteira.model.dtos.*; // <-- IMPORTANDO TODOS OS DTOS
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.service.util.ExchangeRateService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service; // <-- IMPORTADO
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// BUG 1 CORRIGIDO: Adicionado @Service
@Service
public class StockApiProvider implements MarketDataProvider {
    private static final Logger logger = LoggerFactory.getLogger(StockApiProvider.class);

    private final WebClient alphaVantageWebClient;
    private final String alphaVantageApiKey;
    private BigDecimal usdToBrlRate = BigDecimal.ONE;
    private final ExchangeRateService exchangeRateService;

    public StockApiProvider(
            @Value("${alphavantage.apikey}") String alphaVantageApiKey,
            WebClient.Builder webClientBuilder, ExchangeRateService exchangeRateService) {
        this.alphaVantageApiKey = alphaVantageApiKey;
        this.alphaVantageWebClient = webClientBuilder
                .baseUrl("https://www.alphavantage.co")
                .build();
        this.exchangeRateService = exchangeRateService;
    }

    // BUG 3 CORRIGIDO: Implementação do método supports.
    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.STOCK || assetType == AssetType.ETF;
    }

    // BUG 3 CORRIGIDO: Implementação do método initialize para buscar a taxa de câmbio.
    @Override
    public Mono<Void> initialize() {
        return exchangeRateService.fetchUsdToBrlRate()
                .doOnSuccess(rate -> {
                    if (rate != null) {
                        this.usdToBrlRate = rate;
                    } else {
                        logger.warn("Não foi possível obter a taxa de câmbio do Yahoo. Usando o valor anterior/padrão: {}", this.usdToBrlRate);
                    }
                })
                .then();
    }

    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
        return null;
    }

    // BUG 3 CORRIGIDO: Implementação correta e reativa do fetchPrices.
    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        if (assetsToFetch.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(assetsToFetch)
                // O delay de 15s é para respeitar o limite da Alpha Vantage (5 chamadas/min)
                .delayElements(Duration.ofSeconds(15))
                .flatMap(this::fetchSingleStockPrice)
                .doOnError(error -> logger.error("Erro no fluxo de busca de preços de ações: {}", error.getMessage()))
                .onErrorResume(e -> Flux.empty());
    }

    // BUG 4 CORRIGIDO: Método legado fetchStockPrices foi removido.

    // BUG 6 & 7 CORRIGIDO: Agora retorna Mono<PriceData> e usa DTOs públicos.
    private Mono<PriceData> fetchSingleStockPrice(AssetToFetch asset) {
        String tickerForApi = (asset.market() == Market.B3) ? asset.ticker() + ".SA" : asset.ticker();

        return alphaVantageWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/query")
                        .queryParam("function", "GLOBAL_QUOTE")
                        .queryParam("symbol", tickerForApi)
                        .queryParam("apikey", this.alphaVantageApiKey)
                        .build())
                .retrieve()
                .bodyToMono(AlphaVantageQuoteResponse.class)
                .flatMap(response -> (response.quote() != null) ? Mono.just(response.quote()) : Mono.empty())
                .map(quote -> {
                    // Lógica de conversão de moeda
                    if (asset.market() == Market.US) {
                        BigDecimal priceInUsd = quote.price();
                        if (this.usdToBrlRate.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal priceInBrl = priceInUsd.multiply(this.usdToBrlRate);
                            return new GlobalQuote(quote.symbol(), priceInBrl);
                        }
                    }
                    return quote;
                })
                // Mapeamento final para o DTO padronizado PriceData
                .map(quote -> {
                    String ticker = quote.symbol().replace(".SA", "");
                    return new PriceData(ticker, quote.price());
                })
                .doOnError(error -> logger.error("Erro ao buscar preço para {}: {}", asset.ticker(), error.getMessage()))
                .onErrorResume(e -> Mono.empty()); // Se um ticker falhar, não quebra o fluxo dos outros.
    }

    // Lógica para buscar taxa de câmbio, movida para cá.
    private Mono<Void> fetchUsdToBrlRate() {
        logger.info("Buscando taxa de câmbio USD -> BRL para o StockApiProvider...");
        return alphaVantageWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/query")
                        .queryParam("function", "CURRENCY_EXCHANGE_RATE")
                        .queryParam("from_currency", "USD")
                        .queryParam("to_currency", "BRL")
                        .queryParam("apikey", this.alphaVantageApiKey)
                        .build())
                .retrieve()
                .bodyToMono(ExchangeRateResponse.class)
                .map(ExchangeRateResponse::exchangeRateData)
                .filter(Objects::nonNull)
                .doOnSuccess(rateData -> {
                    if (rateData.exchangeRate() != null) {
                        this.usdToBrlRate = rateData.exchangeRate();
                        logger.info("Taxa de câmbio USD -> BRL atualizada para: {}", this.usdToBrlRate);
                    } else {
                        logger.warn("Não foi possível atualizar a taxa de câmbio. Usando o valor anterior: {}", this.usdToBrlRate);
                    }
                })
                .doOnError(error -> logger.error("Erro ao buscar taxa de câmbio: {}", error.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}