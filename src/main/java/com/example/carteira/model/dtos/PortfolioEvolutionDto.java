package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PortfolioEvolutionDto {
    private List<PortfolioEvolutionPointDto> evolution;
}
