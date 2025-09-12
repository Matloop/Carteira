package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class PortfolioEvolutionPointDto {
    private String date;
    private BigDecimal heritage;
    private BigDecimal valueApplied;
}
