package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {
    Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch);

    boolean supports(AssetType assetType);

    Mono<Void> initialize();

    Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date);
}
