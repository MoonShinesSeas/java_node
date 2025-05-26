package com.example.model.connect;

import java.io.Serializable;

public class ConnectMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nodeIndex;

    private String data;

    public ConnectMessage() {
    }

    public ConnectMessage(int type, String data) {
        this.data = data;
    }

    public void setNodeIndex(String nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    public String getNodeIndex() {
        return this.nodeIndex;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getData() {
        return this.data;
    }
}
