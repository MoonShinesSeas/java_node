package com.example.model.PBFT;

import com.example.model.client.Request;
import lombok.Data;

import java.util.List;

//客户端聚合消息
@Data
public class Message {
    private String agg_signature;
    private List<String> clients;
    private List<Request> requests;
}
