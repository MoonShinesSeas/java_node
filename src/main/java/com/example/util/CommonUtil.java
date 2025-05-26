package com.example.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CommonUtil {
    /**
     * 获取本地ip
     * 
     * @return
     */
    public static String getLocalIp() {
        try {
            InetAddress ip4 = InetAddress.getLocalHost();
            return ip4.getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return "";
    }
    /**
     * 获取当前时间（精确到毫秒）的字符串
     * @return 返回时间字符串yyyy-MM-dd HH:mm:ss.SSS
     */
    public static String getTheTimeInMilliseconds() {
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date date = new Date();
        return time.format(date);
    }


}

