package com.example.model.client;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Data
public class AppClient {
    @Value("${client.ip}")
    private String ip;

    @Value("${client.port}")
    private int port;
}
