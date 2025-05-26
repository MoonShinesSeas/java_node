package com.example.model;

import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.annotation.JSONType;
import com.example.model.client.Request;

import java.io.Serializable;
import java.util.List;

@JSONType(orders = {"index","hash","previousHash","timestamp","blockInfo"})
public class Block implements Serializable {

    private static final long serialVersionUID = 1L;

    private int index;

    private String hash;

    private String previousHash;

    private long timestamp;

    private List<Request> blockInfo;

    public int getIndex() {
        return this.index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public long getTimestamp() {
        return this.timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public List<Request> getBlockInfo() {return this.blockInfo;}

    public void setBlockInfo(List<Request> blockInfo) {
        this.blockInfo = blockInfo;
    }

    public String getPreviousHash() {
        return this.previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getHash() {
        return this.hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}