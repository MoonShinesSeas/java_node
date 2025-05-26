package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.model.transaction.Product;
import com.example.model.transaction.Transaction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BlockUtil {

    private final static String path = "./data/node4/";
    // 解析区块信息到对象
    public static void parseBlockInfo(String blockHash, JSONObject blockInfo) {
        int operation = blockInfo.getIntValue("operation");
        switch (operation) {
            case 0:
                parseProduct(blockHash, blockInfo,0);
                break;
            case 1:
                parseTransaction(blockHash, blockInfo);
                break;
            default:
                throw new IllegalArgumentException("未知操作类型: " + operation);
        }
    }

    // 解析 Product,version代表上架与下架
    private static void parseProduct(String blockHash, JSONObject blockInfo,Integer verison) {
        // 提取关键字段
        String requestId = blockInfo.getString("requestId");
        long timestamp = blockInfo.getLongValue("timestamp");
        String data = blockInfo.getString("data");

        JSONObject dataJson = JSON.parseObject(data);

        Product product = new Product();
        product.setRequestId(requestId);
        product.setOrg(dataJson.getString("org"));
        product.setOwner(dataJson.getString("owner"));
        product.setType(dataJson.getString("type"));
        product.setAmount(dataJson.getInteger("amount"));
        product.setPrice(dataJson.getFloat("price"));
        product.setStatus(0); // 默认状态
        product.setBlock_hash(blockHash);
        product.setTimestamp(timestamp);
        product.setVersion(verison);
        saveProduct(product);
    }

    public static void parseTransaction(String blockHash, JSONObject blockInfo) {
        // 提取关键字段
        String requestId = blockInfo.getString("requestId");
        long timestamp = blockInfo.getLongValue("timestamp");
        String data = blockInfo.getString("data");
        JSONObject dataJson = JSON.parseObject(data); // 二次解析data字段

        // 填充Transaction对象
        Transaction transaction = new Transaction();
        transaction.setRequestId(requestId);
        transaction.setProductId(dataJson.getString("productId")); // 注意字段名是product_id
        transaction.setSaler(dataJson.getString("saler"));
        transaction.setBuyer(dataJson.getString("buyer"));
        transaction.setAmount(dataJson.getFloat("amount"));
        transaction.setBalance(dataJson.getFloat("balance"));
        transaction.setBlock_hash(blockHash);
        transaction.setTimestamp(timestamp); // 使用blockInfo的timestamp
        saveTransaction(transaction);

        updateProductVersion(transaction.getProductId());
    }

    private static void updateProductVersion(String productId) {
        if (productId == null || productId.isEmpty()) {
            System.err.println("无效的productId");
            return;
        }
        List<Product> products = readProducts();
        boolean found = false;
        for (Product product : products) {
            if (product.getRequestId().equals(productId)) {
                product.setVersion(product.getVersion() + 1);
                saveProduct(product);
                found = true;
                break;
            }
        }
        if (!found) {
            System.err.println("未找到对应Product: " + productId);
        }
    }

    // 保存Product到./data/product.json
    public static void saveProduct(Product product) {
        List<Product> products = readProducts();
        // 移除旧版本Product（如果存在）
        products.removeIf(p -> p.getRequestId().equals(product.getRequestId()));
        // 添加新版本Product
        products.add(product);
        // 覆盖写入文件
        saveAllProducts(products);
    }

    // 新增方法：覆盖保存所有Product
    private static void saveAllProducts(List<Product> products) {
        File file = new File(path + "product.json");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (Product product : products) {
                writer.write(JSON.toJSONString(product));
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 保存Transaction到./data/transaction.json
    public static void saveTransaction(Transaction transaction) {
        String jsonString = JSON.toJSONString(transaction);
        saveToFile(path+"transaction.json", jsonString);
    }

    // 通用保存方法，追加写入文件，UTF-8编码
    private static void saveToFile(String filePath, String content) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();

        // 确保目录存在
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 追加写入文件，使用UTF-8编码
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writer.write(content);
            writer.newLine(); // 换行分隔每个JSON对象
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 读取 Product 数据列表
    public static List<Product> readProducts() {
        return readFromFile(path+"product.json", Product.class);
    }

    // 读取 Transaction 数据列表
    public static List<Transaction> readTransactions() {
        return readFromFile(path+"/transaction.json", Transaction.class);
    }

    // 通用读取方法
    private static <T> List<T> readFromFile(String filePath, Class<T> clazz) {
        List<T> resultList = new ArrayList<>();
        File file = new File(filePath);

        // 如果文件不存在，直接返回空列表
        if (!file.exists()) {
            return resultList;
        }

        // 读取文件内容，使用UTF-8编码
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    T obj = JSON.parseObject(line, clazz);
                    resultList.add(obj);
                } catch (Exception e) {
                    System.err.println("解析JSON行失败: " + line);
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("读取文件失败: " + filePath);
            e.printStackTrace();
        }
        return resultList;
    }
}
