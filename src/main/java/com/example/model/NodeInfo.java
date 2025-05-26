package com.example.model;

//节点信息
public class NodeInfo {
    private String ip;

    private int port;

    public String getIP() {
        return this.ip;
    }

    public void setIP(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String toString() {
        return "/" + this.ip + ":" + this.port + ":server";
    }
}
