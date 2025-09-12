package com.example.carteira.controller;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.service.MarketDataService;
import com.example.carteira.service.PortfolioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    private final PortfolioService portfolioService;
    private final MarketDataService marketDataService;

    public PortfolioController(PortfolioService portfolioService, MarketDataService marketDataService) {
        this.portfolioService = portfolioService;
        this.marketDataService = marketDataService;
    }


    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshData() {
        marketDataService.refreshAllMarketData();
        return ResponseEntity.ok(Map.of("message", "A atualização dos dados de mercado foi iniciada."));
    }


    @GetMapping("/dashboard")
    public ResponseEntity<PortfolioDashboardDto> getPortfolioDashboard() {
        // A chamada ao método principal do serviço está correta.
        return ResponseEntity.ok(portfolioService.getPortfolioDashboardData());
    }

    @GetMapping("/evolution")
    public ResponseEntity<PortfolioEvolutionDto> getPortfolioEvolution() {
        return ResponseEntity.ok(portfolioService.getPortfolioEvolutionData());
    }


}