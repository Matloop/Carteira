package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;

import com.example.carteira.model.enums.Market;
import com.example.carteira.service.util.ExchangeRateService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@Primary
public class WebScraperService implements MarketDataProvider {
    private static final Logger logger = LoggerFactory.getLogger(WebScraperService.class);
    private static final String BASE_URL = "https://finance.yahoo.com/quote/";
    private final ExchangeRateService exchangeRateService;
    private BigDecimal usdToBrlRate = BigDecimal.ONE;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";
    private final WebClient webClient;

    public WebScraperService(ExchangeRateService exchangeRateService, WebClient.Builder webClientBuilder) {
        this.exchangeRateService = exchangeRateService;
        this.webClient = webClientBuilder.baseUrl("https://query1.finance.yahoo.com").build();
    }

    private PriceData extractPriceFromDocument(Document doc, String originalTicker) throws NumberFormatException {
        // Seletor específico e ancorado para evitar capturar preços de outros lugares da página.
        Element priceElement = doc.selectFirst("[data-testid=\"quote-hdr\"] [data-testid=\"qsp-price\"]");

        if (priceElement != null) {
            // Usa .text() para pegar o conteúdo do <span>.
            String priceText = priceElement.text();

            // Isso lida com formatos como "1,234.56" e "1.234,56".
            priceText = priceText.replace(",", "");

            BigDecimal price = new BigDecimal(priceText);
            logger.info("Preço extraído com sucesso para {}: {}", originalTicker, price);
            return new PriceData(originalTicker, price);
        } else {
            logger.warn("Elemento de preço não encontrado no documento HTML para o ticker: {}. A estrutura da página pode ter mudado.", originalTicker);
            return null;
        }
    }

    private Mono<PriceData> fetchPriceViaSearch(AssetToFetch asset) {
        final String searchTerm = asset.ticker();
        logger.info("Ticker '{}' não encontrado diretamente. Tentando via API de busca...", searchTerm);

        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/finance/search")
                        .queryParam("q", searchTerm)
                        .build())
                .retrieve()
                .bodyToMono(YahooSearchResponse.class)
                .flatMap(response -> {
                    if (response.quotes() == null || response.quotes().isEmpty()) {
                        logger.warn("Nenhum resultado encontrado na API de busca do Yahoo para o termo: {}", searchTerm);
                        return Mono.empty();
                    }

                    // CORREÇÃO: Filtra os resultados para encontrar o ticker do tipo INDEX.
                    // Se não encontrar um INDEX, pega o primeiro resultado como fallback.
                    String foundTicker = response.quotes().stream()
                            .filter(q -> "INDEX".equalsIgnoreCase(q.quoteType()))
                            .findFirst()
                            .map(YahooQuote::symbol)
                            .orElse(response.quotes().get(0).symbol()); // Fallback para o primeiro resultado

                    logger.info("Busca via API encontrou o ticker: {} para o termo '{}'", foundTicker, searchTerm);

                    // O mercado aqui é desconhecido, então passamos nulo.
                    // O fetchSingleStockPrice não precisa do mercado para índices.
                    AssetToFetch foundAsset = new AssetToFetch(foundTicker, null);

                    return fetchSingleStockPrice(foundAsset)
                            .map(priceData -> new PriceData(asset.ticker(), priceData.price()));
                })
                .doOnError(error -> logger.error("Erro no fluxo da API de busca para {}: {}", searchTerm, error.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        if (assetsToFetch.isEmpty()) return Flux.empty();
        return Flux.fromIterable(assetsToFetch)
                .flatMap(this::fetchSingleStockPrice);
    }

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.STOCK || assetType == AssetType.ETF;
    }

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

    private Mono<PriceData> fetchSingleStockPrice(AssetToFetch asset) {
        String tickerForApi = asset.ticker();

        // Adiciona o sufixo .SA apenas se for mercado B3 E não tiver um ponto (para evitar B3SA3.SA.SA)
        if (asset.market() == Market.B3 && !tickerForApi.contains(".")) {
            tickerForApi += ".SA";
        }
        String url = BASE_URL + tickerForApi;

        return Mono.fromCallable(() -> {
                    logger.debug("Tentativa de scraping direto na URL: {}", url);
                    Document doc = Jsoup.connect(url)
                            .userAgent(USER_AGENT)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .get();

                    // Delega a lógica de extração para o método auxiliar
                    return extractPriceFromDocument(doc, asset.ticker());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(priceData -> { // A conversão de moeda continua aqui
                    if (asset.market() == Market.US && priceData != null) {
                        BigDecimal priceInUsd = priceData.price();
                        if (this.usdToBrlRate.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal priceInBrl = priceInUsd.multiply(this.usdToBrlRate)
                                    .setScale(2, RoundingMode.HALF_UP);
                            logger.info("Convertendo {}: ${} -> R$ {}", asset.ticker(), priceInUsd, priceInBrl);
                            return new PriceData(asset.ticker(), priceInBrl);
                        } else {
                            logger.error("Taxa de câmbio USD/BRL inválida ({}). Não foi possível converter o preço de {}.", this.usdToBrlRate, asset.ticker());
                        }
                    }
                    return priceData;
                })
                .filter(Objects::nonNull)
                .doOnError(error -> logger.error("Erro na tentativa de busca direta para {}: {}", asset.ticker(), error.getMessage()))
                .onErrorResume(e -> {
                    // Lógica de fallback para a busca
                    if (e instanceof org.jsoup.HttpStatusException hse && hse.getStatusCode() == 404) {
                        return fetchPriceViaSearch(asset);
                    }
                    return Mono.empty();
                });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooSearchResponse(@JsonProperty("quotes") List<YahooQuote> quotes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooQuote(String symbol, String shortname, String quoteType) {}

}
