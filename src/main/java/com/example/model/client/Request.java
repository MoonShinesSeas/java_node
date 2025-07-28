package com.example.model.client;

import com.alibaba.fastjson.annotation.JSONType;
import com.example.util.CryptoUtil;
import lombok.Data;

import java.io.Serializable;

@Data
@JSONType(orders = { "clientId", "requestId", "timestamp", "operation", "data" })
public class Request implements Serializable {
    private static final long serialVersionUID = 1L;
    // 基础信息
    private String clientId; // 客户端唯一标识

    private String requestId; // 请求唯一标识

    private long timestamp; // 时间戳（防重放）

    private Integer operation; // 操作指令(0 买，1 卖)

    private String data; // 存放序列化的数据(根据operation进行判断，如果为0返回的就是product类，如果为1返回transaction类)

    public Request() {} // 无参构造函数
    public Request(String clientId, Integer operation) {
        this.clientId = clientId;
        this.requestId = CryptoUtil.UUID();
        this.timestamp = System.currentTimeMillis();
        this.operation = operation;
    }
}
