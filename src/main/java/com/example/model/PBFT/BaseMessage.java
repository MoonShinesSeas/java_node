package com.example.model.PBFT;

import com.example.model.Block;
import lombok.Data;

@Data
public class BaseMessage {
    private int type;
    private int view;
    private int sequence;
    private Block block;// 消息内容
    private String nodeIndex;
    private String data;

    @Override
    public String toString() {
        // 固定字段顺序，确保序列化一致性
        return "type=" + type +
                "view=" + view +
                "sequence=" + sequence +
                "nodeIndex=" + nodeIndex +
                "data=" + data;
    }
}
