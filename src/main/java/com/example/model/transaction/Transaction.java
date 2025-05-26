package com.example.model.transaction;

import lombok.Data;

@Data
public class Transaction {
    private String requestId;
    private String productId;
    private String saler;
    private String buyer;
    private Float amount;
    private Float balance;
    private String block_hash;
    private Long timestamp;
}
