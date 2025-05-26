package com.example.network;

import com.example.model.AllNodeInfo;

import java.nio.channels.SocketChannel;

public class ChannelUtil {
    public static void close(SocketChannel channel) {
        if (channel == null) return;

        try {
            // 从全局通道表移除
            String channelKey = AllNodeInfo.findChannelKey(channel);
            if (channelKey != null) {
                AllNodeInfo.channels.remove(channelKey);
                System.out.println("移除: " + channelKey);
            }

            // 关闭物理连接
            if (channel.isOpen()) {
                System.out.println("关闭: "
                        + channel.getRemoteAddress());
                channel.close();
            }
        } catch (Exception e) {
            System.err.println("Error closing channel: " + e.getMessage());
        }
    }
}
