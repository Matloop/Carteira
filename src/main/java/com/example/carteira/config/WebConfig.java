package com.example.carteira.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**") // Aplica a configuração a todos os endpoints que começam com /api
                        .allowedOrigins("http://localhost:3000", "http://localhost:3001") // Permite requisições SOMENTE desta origem
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Métodos HTTP permitidos
                        .allowedHeaders("*") // Permite todos os cabeçalhos na requisição
                        .allowCredentials(true); // Permite o envio de cookies (importante para autenticação futura)
            }
        };
    }
}