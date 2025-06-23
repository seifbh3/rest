package com.safecode.models;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Getter
@Setter
@ToString
public class ChatRequest {
    private String model;
    private List<?> xxxx;
    private Double temperature;
    private Integer n;
    public String blabla;
}