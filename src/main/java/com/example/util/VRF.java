package com.example.util;

import com.alibaba.fastjson.JSON;
import com.example.model.PBFTInfo;
import com.example.network.Client;
import it.unisa.dia.gas.jpbc.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;

public class VRF {
    private static final Logger logger = LoggerFactory.getLogger(VRF.class);

    // 改进的Proof类结构
    public static class Proof {
        public final String challenge; // Base64编码的Zr元素
        public final String response;  // Base64编码的Zr元素
        public final String y;         // Base64编码的G1元素

        public Proof(String challenge, String response, String y) {
            this.challenge = challenge;
            this.response = response;
            this.y = y;
        }
    }

    // 安全地将消息哈希到G1曲线
    public static Element hashToG1Curve(String hash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(hash.getBytes(StandardCharsets.UTF_8));
            return Common.pairing.getG1().newElementFromHash(hashBytes, 0, hashBytes.length);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    // 安全哈希函数（SHA-256）
    private static Element hashToZrCurveSecure(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            return Common.pairing.getZr().newElementFromHash(hashBytes, 0, hashBytes.length);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    // 挑战生成方法
    public static Element getChallenge(Element G, Element H, Element y, Element K, Element publicKey, String hash) {
        // 使用 Base64 编码元素内容
        String GBase64 = Base64.getEncoder().encodeToString(G.toBytes());
        String HBase64 = Base64.getEncoder().encodeToString(H.toBytes());
        String YBase64 = Base64.getEncoder().encodeToString(y.toBytes());
        String KBase64 = Base64.getEncoder().encodeToString(K.toBytes());
        String pkBase64 = Base64.getEncoder().encodeToString(publicKey.toBytes());
        String hashBase64 = Base64.getEncoder().encodeToString(hash.getBytes());
        String input = GBase64 + HBase64 + YBase64 + KBase64 + pkBase64 + hashBase64;

        // 使用 SHA-256 替代不安全的 toString()
        return hashToZrCurveSecure(input);
    }

    // 计算响应Zr
    public static Element response(Element sk, Element challenge, Element r) {
        return r.add(challenge.mulZn(sk));// k+c*sk
    }

    // 生成Proof
    public static String getProof(String hash, Element pk, Element sk,String index) {
        Element sk_immutable = sk.getImmutable();
        // 1.计算y=sk*h
        Element h = hashToG1Curve(hash).getImmutable();
        Element y = h.mulZn(sk).getImmutable();

        // 随机标量k
        Element k = Common.pairing.getZr().newRandomElement().getImmutable();
        Element g = base64ToG1Element(Common.g1_String).getImmutable();
        // 临时点K=kg
        Element K = g.mulZn(k).getImmutable();
        // 挑战
        Element challenge = getChallenge(g, h, y, K, pk, hash).getImmutable();
        // 响应
        Element response = response(sk_immutable, challenge, k).getImmutable();

        String value = CryptoUtil.SHA256(y.toString());// 用于比较的输出值

        // 5. 构建可序列化的Proof
        Proof proof = new Proof(
                elementToBase64(challenge),
                elementToBase64(response),
                elementToBase64(y));
        // 存储随机值用于选举
        PBFTInfo.setValue(index,value);

        return JSON.toJSONString(proof);
    }

    // 验证逻辑
    public static void verify(String hash, Element pk, String data, String index) {
        try {
            Proof proof = JSON.parseObject(data, Proof.class);
            Element h = hashToG1Curve(hash).getImmutable();
            Element g = base64ToG1Element(Common.g1_String).getImmutable();

            Element challenge = base64ToZrElement(proof.challenge).getImmutable();
            Element s = base64ToZrElement(proof.response).getImmutable();
            Element y = base64ToG1Element(proof.y).getImmutable();
            Element g_s = g.duplicate().mulZn(s);
            Element pk_c = pk.duplicate().mulZn(challenge);
            Element U = g_s.duplicate().sub(pk_c).getImmutable();// g*s-pk*c=g*(k+c*sk)-g*sk*c=g*k

            Element challenge_prime = getChallenge(g, h, y, U, pk, hash);

            if (!challenge_prime.isEqual(challenge)) {
                logger.error("验证失败");
                return;
            }
            //将G1元素y的字节直接转为Zr元素
            String value = CryptoUtil.SHA256(y.toString());// 用于比较的输出值
            PBFTInfo.setValue(index, value);
            logger.info("验证{} {}成功", index, value );
        } catch (Exception e) {
            logger.error("验证过程中发生异常{}" , e.getMessage());
        }
    }

    public static String getMinIndex(Map<String, String> values) {
        return values.entrySet().stream()
                .min(Comparator.comparing(entry -> new BigInteger(1, entry.getValue().getBytes(StandardCharsets.UTF_8))))
                .map(Entry::getKey)
                .orElse(null);
    }

    // 辅助方法
    private static String elementToBase64(Element element) {
        return Base64.getEncoder().encodeToString(element.toBytes());
    }

    public static Element base64ToG1Element(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return Common.pairing.getG1().newElementFromBytes(bytes);
    }

    public static Element base64ToZrElement(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return Common.pairing.getZr().newElementFromBytes(bytes);
    }
}