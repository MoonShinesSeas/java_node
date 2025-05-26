package com.example.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import it.unisa.dia.gas.jpbc.Element;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

public class BLS {
    // 将 Base64 解码为 Element（G1群）
    public static Element encodeg1(String g1) {
        byte[] bytes = Base64.getDecoder().decode(g1);
        return Common.pairing.getG1().newElementFromBytes(bytes); // 解码到 G1
    }

    public static Element GeneratePrivateKey() {
        Element sk = Common.pairing.getZr().newRandomElement();
        return sk;
    }

    public static Element GeneratePublicKey(Element sk) {
        Element g1 = encodeg1(Common.g1_String);

        Element pk = g1.duplicate().mulZn(sk);
        return pk;
    }

    public static Element sign(String msg, Element sk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] m = digest.digest(msg.getBytes(StandardCharsets.UTF_8)); // 使用SHA-256
            Element h = Common.pairing.getG2().newElementFromHash(m, 0, m.length).getImmutable();// hash->G2

            Element sig = h.duplicate().mulZn(sk);// sig=h*sk
            return sig;
        } catch (Exception e) {
            System.out.println("Sign Error" + e.getMessage());
            return null;
        }
    }

    // 验证签名
    public static Boolean verify(Element sig, Element pk, String msg) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] m = digest.digest(msg.getBytes(StandardCharsets.UTF_8)); // 使用SHA-256
            Element h = Common.pairing.getG2().newElementFromHash(m, 0, m.length).getImmutable();// hash->G2
            Element g1 = encodeg1(Common.g1_String);

            Element pl = Common.pairing.pairing(g1, sig);// e(g_1,h*sk)=e(g_1,sig)=e(h,pk)=e(g_1*sk,h)
            Element pr = Common.pairing.pairing(pk, h);

            if (pl.isEqual(pr))
                return true;
            return false;
        } catch (Exception e) {
            System.out.println("Verify Error" + e.getMessage());
            return false;
        }
    }

    // 签名聚合m-m签名
    public static Element AggregateSignatures(List<Element> signatures) {
        Element aggregatedSignature = Common.pairing.getG2().newZeroElement();
        for (Element sig : signatures)
            aggregatedSignature.add(sig);
        return aggregatedSignature;
    }

    // 聚合公钥m-m签名
    public static Element AggregatePublicKey(List<Element> pks) {
        Element aggregatedPublicKeys = Common.pairing.getG1().newZeroElement();
        for (Element pk : pks) {
            pk = pk.getImmutable();
            aggregatedPublicKeys.add(pk);
        }
        return aggregatedPublicKeys;
    }

    // 聚合签名验证方法，多个消息
    public static Boolean AggregateVerifyDifferentMessages(Element aggregatedSignature, List<Element> publicKeys,
                                                           List<String> messages) {
        if (publicKeys == null || messages == null || publicKeys.size() != messages.size()) {
            throw new IllegalArgumentException("公钥和消息列表必须非空且长度一致");
        }

        Element gt = Common.pairing.getGT().newOneElement(); // 初始化为GT群的单位元
        Element g1 = encodeg1(Common.g1_String); // G1群的生成元

        for (int i = 0; i < publicKeys.size(); i++) {
            Element pk = publicKeys.get(i);
            String msg = messages.get(i);

            // 计算当前消息的哈希到G2群
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] m = digest.digest(msg.getBytes(StandardCharsets.UTF_8)); // 使用SHA-256
                Element h = null;
                h = Common.pairing.getG2().newElementFromHash(m, 0, m.length).getImmutable();// hash->G2
                // 计算配对e(pk_i, h_i)并累乘到乘积
                Element e = Common.pairing.pairing(pk, h);
                gt = gt.mul(e);
            } catch (Exception e) {
                System.out.println("Verify Error" + e.getMessage());
                return false;
            }
        }

        // 计算左边的配对e(g1, 聚合签名)
        Element left = Common.pairing.pairing(g1, aggregatedSignature);

        return left.isEqual(gt);
    }

    // 私钥格式化
    public static String SkToPem(Element element) {
        ByteArrayOutputStream pemStream = new ByteArrayOutputStream();
        try {
            pemStream.write(("-----BEGIN PRIVATE KEY-----\n").getBytes());
            byte[] elementBytes = element.toBytes();
            String base64Encoded = Base64.getEncoder().encodeToString(elementBytes);
            int lineLength = 64;
            for (int i = 0; i < base64Encoded.length(); i += lineLength) {
                int endIndex = Math.min(i + lineLength, base64Encoded.length());
                pemStream.write((base64Encoded.substring(i, endIndex) + "\n").getBytes());
            }
            pemStream.write(("-----END PRIVATE KEY-----\n").getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new String(pemStream.toByteArray());
    }

    // 公钥格式化
    public static String PkToPem(Element element) {
        ByteArrayOutputStream pemStream = new ByteArrayOutputStream();
        try {
            pemStream.write(("-----BEGIN PUBLIC KEY-----\n").getBytes());
            byte[] elementBytes = element.toBytes();
            String base64Encoded = Base64.getEncoder().encodeToString(elementBytes);
            int lineLength = 64;
            for (int i = 0; i < base64Encoded.length(); i += lineLength) {
                int endIndex = Math.min(i + lineLength, base64Encoded.length());
                pemStream.write((base64Encoded.substring(i, endIndex) + "\n").getBytes());
            }
            pemStream.write(("-----END PUBLIC KEY-----\n").getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new String(pemStream.toByteArray());
    }

    // pem格式转公钥
    public static Element PemToPk(String pem) {
        // 去除 PEM 格式的头尾标记
        String base64Content = pem.replace("-----BEGIN PUBLIC KEY-----\n", "")
                .replace("-----END PUBLIC KEY-----\n", "")
                .replaceAll("\\s", "");
        byte[] decodedBytes = Base64.getDecoder().decode(base64Content);

        return Common.pairing.getG1().newElementFromBytes(decodedBytes);
    }

    public static Element byteToPk(byte[] pk) {
        return Common.pairing.getG1().newElementFromBytes(pk);
    }

    public static Element PemToSk(String pem) {
        // 去除 PEM 格式的头尾标记
        String base64Content = pem.replace("-----BEGIN PRIVATE KEY-----\n", "")
                .replace("-----END PRIVATE KEY-----\n", "")
                .replaceAll("\\s", "");
        byte[] decodedBytes = Base64.getDecoder().decode(base64Content);

        return Common.pairing.getZr().newElementFromBytes(decodedBytes);
    }

    // 读取 BLS 私钥
    public static Element PemToSk(InputStream inputStream) throws IOException {
        String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        Element keyElement = Common.pairing.getZr().newElementFromBytes(keyBytes);
        return keyElement;
    }

    // 将PEM格式的签名转换为Element
    public static Element PemToSig(String pem) {
        String base64Content = pem.replace("-----BEGIN SIGNATURE-----\n", "")
                .replace("-----END SIGNATURE-----\n", "")
                .replaceAll("\\s", "");
        byte[] decodedBytes = Base64.getDecoder().decode(base64Content);
        return Common.pairing.getG2().newElementFromBytes(decodedBytes);
    }

    // 解析 byte[] 类型的签名数据
    public static Element PemToSig(byte[] pem) {
        return Common.pairing.getG2().newElementFromBytes(pem);
    }

    // 将签名转换为PEM格式
    public static String SigToPem(Element sig) {
        ByteArrayOutputStream pemStream = new ByteArrayOutputStream();
        try {
            pemStream.write(("-----BEGIN SIGNATURE-----\n").getBytes());
            byte[] sigBytes = sig.toBytes();
            String base64Encoded = Base64.getEncoder().encodeToString(sigBytes);
            int lineLength = 64;
            for (int i = 0; i < base64Encoded.length(); i += lineLength) {
                int endIndex = Math.min(i + lineLength, base64Encoded.length());
                pemStream.write((base64Encoded.substring(i, endIndex) + "\n").getBytes());
            }
            pemStream.write(("-----END SIGNATURE-----\n").getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new String(pemStream.toByteArray());
    }

    public static SubjectPublicKeyInfo encodePublicKey(Element pk) throws IOException {
        // 1. 获取BLS公钥的原始字节
        byte[] bytes = pk.toBytes();

        // 2. 直接包装为BIT STRING（未使用位数为0）
        DERBitString bitStr = new DERBitString(bytes, 0);

        // 4. 定义BLS算法标识符
        AlgorithmIdentifier algId = new AlgorithmIdentifier(Common.oid);

        // 5. 构建SubjectPublicKeyInfo
        return new SubjectPublicKeyInfo(algId, bitStr);
    }

    public static Element decodePublicKey(byte[] publicKey) throws Exception {
        // 手动解析DER编码
        ASN1InputStream asn = new ASN1InputStream(publicKey);
        ASN1Sequence seq = (ASN1Sequence) asn.readObject();
        asn.close();
        // 提取算法标识符
        AlgorithmIdentifier oid = AlgorithmIdentifier.getInstance(seq.getObjectAt(0));
        if (!oid.getAlgorithm().equals(Common.oid)) {
            throw new IllegalArgumentException("证书公钥算法不是BLS");
        }

        // 提取BIT STRING
        DERBitString bitStr = (DERBitString) seq.getObjectAt(1);
        byte[] bytes = bitStr.getOctets();

        // 转换为BLS的Element类型
        return Common.pairing.getG1().newElementFromBytes(bytes);
    }
}