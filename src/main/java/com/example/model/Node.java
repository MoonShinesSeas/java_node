package com.example.model;

import com.example.util.BLS;
import com.example.util.CommonUtil;
import com.example.util.CryptoUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "block")
@Data
@Component
public class Node {
    private String ip = CommonUtil.getLocalIp();

    @Value("${block.port}")
    private int port;

    @Value("${block.index}")
    private String index;

    @Value("${block.app_port}")
    private int app_port;

    private Boolean isPrimary = false;

    // 失效节点
    private Integer f = 1;
    // 视图号
    private Integer view = 0;
    // 序列号
    private Integer sequence = 0;
}
