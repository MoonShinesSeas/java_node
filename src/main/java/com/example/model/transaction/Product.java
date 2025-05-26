package com.example.model.transaction;

import lombok.Data;

@Data
public class Product {
    private String requestId;
    private String org;
    private String owner;
    private String type;
    private Integer amount;
    private Float price;
    private Integer status;
    private String block_hash;
    private Long timestamp;
    private Integer version;
}
