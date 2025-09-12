package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.repository.TransactionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço Orquestrador de Dados de Mercado.
 *
 * Responsabilidades:
 * 1. Gerenciar um cache central de preços de ativos (`priceCache`).
 * 2. Coordenar uma lista de provedores de dados (`MarketDataProvider`).
 * 3. Inicializar todos os provedores na inicialização da aplicação.
 * 4. Agendar uma tarefa recorrente para atualizar os preços de todos os ativos da carteira.
 * 5. Delegar a busca de dados ao provedor correto para cada tipo de ativo.
 */
@Service
public class MarketDataService {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataService.class);

    private final TransactionRepository transactionRepository;
    private final List<MarketDataProvider> providers;
    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Construtor que injeta todas as implementações de MarketDataProvider
     * encontradas pelo Spring, além do repositório de transações.
     */
    public MarketDataService(TransactionRepository transactionRepository, List<MarketDataProvider> providers) {
        this.transactionRepository = transactionRepository;
        this.providers = providers;
        logger.info("MarketDataService carregado com {} provedor(es) de dados.", providers.size());
    }

    /**
     * Inicializa o serviço após a construção.
     * Primeiro, inicializa todos os provedores de dados e, em caso de sucesso,
     * agenda a atualização recorrente de preços.
     */
    // DENTRO DE MarketDataService.java

    @PostConstruct
    private void initialize() {
        logger.info("Inicializando todos os provedores de dados...");

        Flux.fromIterable(providers)
                .flatMap(MarketDataProvider::initialize)
                .then() // Aguarda a conclusão de todas as inicializações
                .doOnSuccess(aVoid -> {
                    logger.info("Todos os provedores foram inicializados com sucesso!");
                    logger.info("A primeira busca de dados de preços ocorrerá em 10 segundos.");

                    // CORREÇÃO: Mova o agendador para DENTRO do doOnSuccess.
                    // Isso garante que ele só será agendado DEPOIS que a inicialização (o .then())
                    // for concluída com sucesso.
                    scheduler.scheduleAtFixedRate(this::refreshAllMarketData, 10, 300, TimeUnit.SECONDS);
                })
                .doOnError(error -> {
                    logger.error("Falha ao inicializar um ou mais provedores! A atualização de preços não será agendada.", error);
                    // DECISÃO DE DESIGN: Se a inicialização falhar, não agendamos a tarefa para evitar
                    // executar com dados potencialmente incorretos.
                })
                .subscribe();
    }

    /**
     * Retorna o preço de um ativo específico do cache.
     *
     * @param ticker O ticker do ativo (ex: "PETR4").
     * @return O preço em BigDecimal, ou BigDecimal.ZERO se não for encontrado.
     */
    public BigDecimal getPrice(String ticker) {
        return priceCache.getOrDefault(ticker.toUpperCase(), BigDecimal.ZERO);
    }

    /**
     * Retorna uma visão não modificável de todo o cache de preços.
     */
    public Map<String, BigDecimal> getAllPrices() {
        return Collections.unmodifiableMap(priceCache);
    }

    /**
     * Dispara a atualização de preços para todos os ativos presentes no banco de dados.
     * Este é o método chamado pelo agendador.
     */
    public void refreshAllMarketData() {
        logger.info("ATUALIZAÇÃO GERAL: Iniciando busca de dados para todos os ativos da carteira...");
        List<Transaction> allTransactions = transactionRepository.findAll();
        if (allTransactions.isEmpty()) {
            logger.info("Nenhuma transação na carteira para atualizar.");
            return;
        }
        // Chama o método unificado de atualização
        updatePricesForTransactions(allTransactions);
    }

    public void updatePricesForTransactions(List<Transaction> transactions) {
        updatePricesForTransactions(transactions, "ATUALIZAÇÃO SOB DEMANDA");
    }

    /**
     * Atualiza o cache central com os dados de preço recebidos de um provedor.
     * @param priceData O DTO contendo o ticker e o preço.
     */
    private void updateCache(PriceData priceData) {
        priceCache.put(priceData.ticker().toUpperCase(), priceData.price());
        logger.info("Cache atualizado: {} = {}", priceData.ticker().toUpperCase(), priceData.price());
    }

    /**
     * Encontra o primeiro provedor na lista que suporta o tipo de ativo fornecido.
     * @param assetType O tipo de ativo (STOCK, CRYPTO, etc.).
     * @return um Optional contendo o provedor, ou um Optional vazio se nenhum for encontrado.
     */
    List<MarketDataProvider> findProvidersFor(AssetType assetType) {
        return providers.stream()
                .filter(p -> p.supports(assetType))
                // Ordena a lista para que o provedor @Primary venha primeiro.
                .sorted(Comparator.comparing(p -> !p.getClass().isAnnotationPresent(org.springframework.context.annotation.Primary.class)))
                .collect(Collectors.toList());
    }

    private void updatePricesForTransactions(List<Transaction> transactions, String logContext) {
        if (transactions == null || transactions.isEmpty()) {
            logger.warn("[{}] Chamada com uma lista de transações vazia.", logContext);
            return;
        }
        logger.info("[{}] Iniciando busca para {} transação(ões).", logContext, transactions.size());

        Map<AssetType, List<Transaction>> groupedByType = transactions.stream()
                .collect(Collectors.groupingBy(Transaction::getAssetType));

        groupedByType.forEach((assetType, transactionList) -> {
            // 1. Encontrar a lista ordenada de provedores
            List<MarketDataProvider> availableProviders = findProvidersFor(assetType);

            if (availableProviders.isEmpty()) {
                logger.warn("[{}] Nenhum provedor de dados encontrado para o tipo de ativo: {}", logContext, assetType);
                return;
            }

            Set<AssetToFetch> uniqueAssets = transactionList.stream()
                    .map(t -> new AssetToFetch(t.getTicker().toUpperCase(), t.getMarket()))
                    .collect(Collectors.toSet());

            // 2. Construir a cadeia de fallback reativa
            Flux<PriceData> priceFlux = Flux.empty();

            for (int i = 0; i < availableProviders.size(); i++) {
                final MarketDataProvider currentProvider = availableProviders.get(i);
                final boolean isLastProvider = i == availableProviders.size() - 1;

                Flux<PriceData> providerFlux = Flux.defer(() -> {
                    logger.info("[{}] Tentando provedor '{}' para {} ativo(s) do tipo {}",
                            logContext, currentProvider.getClass().getSimpleName(), uniqueAssets.size(), assetType);
                    return currentProvider.fetchPrices(new ArrayList<>(uniqueAssets));
                });

                if (isLastProvider) {
                    // Se for o último provedor, usamos o resultado dele, mesmo que seja vazio.
                    priceFlux = priceFlux.switchIfEmpty(providerFlux);
                } else {
                    // Se não for o último, tentamos o próximo provedor se este falhar (retornar vazio).
                    priceFlux = priceFlux.switchIfEmpty(providerFlux)
                            .collectList() // Agrupa os resultados
                            .flatMapMany(list -> {
                                if (list.isEmpty()) {
                                    // Se a lista estiver vazia, retorna um Fluxo vazio para acionar o próximo switchIfEmpty
                                    logger.warn("[{}] Provedor '{}' não retornou dados. Tentando fallback...", logContext, currentProvider.getClass().getSimpleName());
                                    return Flux.empty();
                                }
                                // Se houver dados, retorna o Fluxo com os dados
                                return Flux.fromIterable(list);
                            });
                }
            }

            // 3. Inscrever-se no resultado final da cadeia de fallback
            priceFlux.subscribe(
                    this::updateCache,
                    error -> logger.error("[{}] Erro irrecuperável no fluxo de busca para o tipo {}: {}", logContext, assetType, error.getMessage()),
                    () -> logger.info("[{}] Fluxo de preços para o tipo {} concluído.", logContext, assetType)
            );
        });
    }
}