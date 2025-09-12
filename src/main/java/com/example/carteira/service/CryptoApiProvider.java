package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.example.carteira.model.dtos.CoinGeckoCoin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
@Service
public class CryptoApiProvider implements MarketDataProvider {
    private static final Logger logger = LoggerFactory.getLogger(CryptoApiProvider.class);

    private final WebClient coingeckoWebClient;
    private final Map<String, String> tickerToCoingeckoIdCache = new ConcurrentHashMap<>();
    private static final Map<String, String> PRIORITY_MAP = Map.of(
            "BTC", "bitcoin",
            "ETH", "ethereum",
            "USDT", "tether",
            "BNB", "binancecoin",
            "SOL", "solana",
            "USDC", "usd-coin",
            "XRP", "ripple",
            "ADA", "cardano"
    );

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.CRYPTO;
    }

    @Override
    public Mono<Void> initialize() {
        return populateCryptoIdCache();
    }

    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
        String coinGeckoId = mapTickerToCoingeckoId(asset.ticker());
        if(coinGeckoId == null) {
            return Mono.empty();
        }
        String dateString = date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

        return coingeckoWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/coins/" + coinGeckoId + "/history")
                        .queryParam("date", dateString)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> {
                    // A resposta da API tem uma estrutura aninhada
                    JsonNode marketData = response.path("market_data");
                    if (!marketData.isMissingNode() && marketData.has("current_price") && marketData.path("current_price").has("brl")) {
                        BigDecimal price = new BigDecimal(marketData.path("current_price").path("brl").asText());
                        return new PriceData(asset.ticker(), price);
                    }
                    return null; // Será filtrado
                })
                .filter(Objects::nonNull)
                .onErrorResume(e -> {
                    logger.error("Erro ao buscar preço histórico para {} em {}: {}", asset.ticker(), date, e.getMessage());
                    return Mono.empty();
                });

    }

    // 5. O método fetch agora retorna um FLUXO de dados
    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assets) {
        List<String> tickers = assets.stream().map(AssetToFetch::ticker).toList();
        String idsForApi = tickers.stream()
                .map(this::mapTickerToCoingeckoId)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));

        // Constrói o fluxo reativo...
        return coingeckoWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/simple/price").queryParam("ids", idsForApi).queryParam("vs_currencies", "brl").build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                // Transforma a resposta única (JsonNode) em um fluxo de múltiplos resultados (PriceData)
                .flatMapMany(response -> Flux.fromIterable(tickers)
                        // Usamos flatMap aqui. Ele espera que você retorne um Publisher (Mono ou Flux).
                        .flatMap(ticker -> {
                            String coingeckoId = mapTickerToCoingeckoId(ticker);
                            if (response != null && response.has(coingeckoId) && response.get(coingeckoId).has("brl")) {
                                BigDecimal price = new BigDecimal(response.get(coingeckoId).get("brl").asText());
                                // Se encontrarmos um preço, retornamos um Mono contendo o valor.
                                // O flatMap externo vai "desembrulhar" este Mono para nós.
                                return Mono.just(new PriceData(ticker.toUpperCase(), price));
                            }
                            // Se não encontrarmos, retornamos um Mono vazio.
                            // O flatMap externo vai simplesmente descartá-lo.
                            return Mono.empty();
                        })
                );
    }

    public CryptoApiProvider(WebClient.Builder webClientBuilder) {
        this.coingeckoWebClient = webClientBuilder.baseUrl("https://api.coingecko.com/api/v3")
                .build();
    }

    private Mono<Void> populateCryptoIdCache() {
        logger.info("Populando caches");
        tickerToCoingeckoIdCache.clear();
        tickerToCoingeckoIdCache.putAll(PRIORITY_MAP);
        logger.info("Cache de prioridade carregado com {} ativos.", PRIORITY_MAP.size());
        return coingeckoWebClient.get()
                .uri("/coins/list")
                .retrieve()
                .bodyToFlux(CoinGeckoCoin.class)
                // Passo 1: Em vez de coletar para um mapa, colete todos os itens em uma lista.
                // O Mono resultante será um Mono<List<CoinGeckoCoin>>.
                // Passo 2: Quando a lista estiver pronta, use 'doOnNext' para processá-la.
                .doOnNext(coin -> {
                    // A chave é o símbolo em maiúsculas
                    String symbol = coin.symbol().toUpperCase();
                    // [MUDANÇA CRÍTICA] Usa putIfAbsent para não sobrescrever os prioritários.
                    if (symbol != null && !symbol.isBlank()) {
                        tickerToCoingeckoIdCache.putIfAbsent(symbol, coin.id());
                    }
                })
                // Passo 3: Após o processamento, transforme o resultado de volta em um Mono<Void>.
                .then();
    }

    private String mapTickerToCoingeckoId(String ticker) {
        String id = tickerToCoingeckoIdCache.get(ticker.toUpperCase());
        if (id == null) {
            logger.warn("ID não encontrado");
        }
        return id;
    }


}
