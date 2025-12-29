package com.example.springboot4demo.dto;

import java.math.BigDecimal;

public record CreateProductRequest(
        String name,
        BigDecimal price,
        String currency,
        Long categoryId,
        String categoryName
) {}
