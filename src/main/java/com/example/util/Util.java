package com.example.util;

import it.unisa.dia.gas.jpbc.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

public class Util {
    private final static String path = "src/main/resources/certs/";
    private final static Logger logger = LoggerFactory.getLogger(Util.class);

    // 读取 BLS 私钥
    public static Element PemToPk(InputStream inputStream) throws IOException {
        String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return Common.pairing.getG1().newElementFromBytes(keyBytes);
    }

    // 读取 BLS 私钥
    public static Element PemToSk(InputStream inputStream) throws IOException {
        String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return Common.pairing.getZr().newElementFromBytes(keyBytes);
    }

    public static X509Certificate parseCertificate(String filePath) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null)
                content.append(line).append(System.lineSeparator()); // 保留换行符
            // 移除最后一个多余的换行符（如果有的话）
            if (content.length() > 0)
                content.deleteCharAt(content.length() - 1);
        } catch (IOException e) {
            throw new RuntimeException("读取证书文件失败", e);
        }

        try (InputStream is = new ByteArrayInputStream(content.toString().getBytes())) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(is);
        } catch (Exception e) {
            logger.warn("读取证书文件出错{}", e.getMessage());
            return null;
        }
    }

    public static X509Certificate parseCertificateFromString(String cert) {
        try (InputStream is = new ByteArrayInputStream(cert.getBytes())) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(is);
        } catch (Exception e) {
            logger.warn("读取证书字符串出错{}", e.getMessage());
            return null;
        }
    }

    public static String readCertificateFile(String filePath) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null)
                content.append(line).append(System.lineSeparator()); // 保留换行符
            // 移除最后一个多余的换行符（如果有的话）
            if (content.length() > 0)
                content.deleteCharAt(content.length() - 1);
        } catch (IOException e) {
            throw new RuntimeException("读取证书文件失败", e);
        }
        return content.toString();
    }

    /**
     * 读取密钥
     */
    public static Element readPrivateKey(String filePath) {
        // 从磁盘加载根证书私钥
        try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
            return PemToSk(fileInputStream);
        } catch (Exception e) {
            logger.warn("读取私钥出错{}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取密钥
     */
    public static Element readPublicKey(String filePath) {
        // 从磁盘加载根证书私钥
        try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
            return PemToPk(fileInputStream);
        } catch (Exception e) {
            logger.warn("读取公钥出错{}", e.getMessage());
            return null;
        }
    }

    // 从证书提取公钥
    public static Element getCertPublicKey(String cert) {
        X509Certificate client_cert = Util.parseCertificateFromString(cert);

        Element client_pk;
        try {
            client_pk = BLS.decodePublicKey(client_cert.getPublicKey().getEncoded());
        } catch (Exception e) {
            System.out.println("获取公钥失败" + e.getMessage());
            return null;
        }
        return client_pk;
    }

    // 从证书提取公钥
    public static Element getCertPublicKey(X509Certificate cert) {
        Element client_pk;
        try {
            client_pk = BLS.decodePublicKey(cert.getPublicKey().getEncoded());
        } catch (Exception e) {
            System.out.println("获取公钥失败" + e.getMessage());
            return null;
        }
        return client_pk;
    }

    public static X509Certificate getNodeCert(String index) {
        String cert = path + index + "/node.cer";
        return parseCertificate(cert);
    }

    public static Element getNodeSk(String index) {
        String sk = path + index + "/node_sk.cer";
        return readPrivateKey(sk);
    }

    public static Element getNodePk(String index){
        X509Certificate cert = getNodeCert(index);
        return getCertPublicKey(cert);
    }
}
