package com.example.model.PBFT;

import com.example.model.Block;
import com.example.model.client.ClientRequest;
import lombok.Data;

import java.util.List;

// <<PRE-PREPARE, v, n, d,i>
@Data
public class PBFTMessage {
    private String digest;// 客户端消息摘要
    private String signature;

    private List<String> nodes;//聚合的公钥索引

    private Message agg_message;//原始请求，在pre_prepare阶段使用

    private NewView agg_viewChange;//在视图切换阶段

    private BaseMessage base_message;

    public PBFTMessage() {
    }
}
