package com.example.model;

import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AllNodeInfo {
    //节点IP和通道
    public static final Map<String, SocketChannel> channels = new ConcurrentHashMap<>();

    // 节点索引和信息
    public static final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    public static void setNodes(String index, NodeInfo node) {
        nodes.put(index, node);
    }

    public static void removeNode(String index) {
        nodes.remove(index);
    }

    public static NodeInfo getNodes(String index) {
        return nodes.get(index);
    }

    public static void setChannels(String index, SocketChannel channel) {
        channels.put(index, channel);
    }

    public static void removeChannel(String index) {
        try {
            SocketChannel node_channel = channels.remove(index);
            if (node_channel != null)
                node_channel.close();
        } catch (Exception e) {
            System.out.println("移除节点失败" + e.getMessage());
        }
    }

    public static SocketChannel getChannel(String index) {
        return channels.get(index);
    }

    // 打印
    public static void printNodes() {
        System.out.println("====== 当前节点列表 ======");
        System.out.println("总节点数: " + nodes.size());

        nodes.forEach((index, node) -> {
            System.out.printf("IP: %-15s | 端口: %d%n", node.getIP(), node.getPort());
        });

        System.out.println("=======================");
    }

    public static String findChannelKey(SocketChannel targetChannel) {
        for (Map.Entry<String, SocketChannel> entry : channels.entrySet()) {
            if (entry.getValue().equals(targetChannel)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // 新增的打印方法
    public static void printChannels() {
        System.out.println("====== 当前通道列表 ======");
        System.out.println("总通道数: " + channels.size());

        channels.forEach((ip, channel) -> {
            String status = (channel != null && channel.isOpen()) ? "活跃" : "不活跃";
            System.out.printf("IP: %-15s | 状态: %s%n", ip, status);
        });

        System.out.println("=======================");
    }
}
