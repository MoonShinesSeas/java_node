package com.example.util;

import org.springframework.util.DigestUtils;

import java.security.MessageDigest;

public class CryptoUtil {

    /**
     * SHA256散列函数
     *
     * @param str
     * @return
     */
    public static String SHA256(String str) {
        MessageDigest messageDigest;
        String encodeStr = "";
        try {
            messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(str.getBytes("UTF-8"));
            encodeStr = byte2Hex(messageDigest.digest());
        } catch (Exception e) {
            System.out.println("getSHA256 is error" + e.getMessage());
        }
        return encodeStr;
    }

    private static String byte2Hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        String temp;
        for (int i = 0; i < bytes.length; i++) {
            temp = Integer.toHexString(bytes[i] & 0xFF);
            if (temp.length() == 1) {
                builder.append("0");
            }
            builder.append(temp);
        }
        return builder.toString();
    }

    public static String MD5(String str) {
        String resultStr = DigestUtils.md5DigestAsHex(str.getBytes());
        return resultStr.substring(4, resultStr.length());
    }

    public static String UUID() {
        return java.util.UUID.randomUUID().toString().replaceAll("\\-", "");
    }
}