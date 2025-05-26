package com.example.model;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MerkleTrees {

    private final List<String> transactions;

    private String root;

    public MerkleTrees(List<String> transactions) {
        this.transactions = Objects.requireNonNull(transactions);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Transaction list cannot be empty");
        }
        this.root = buildTree(new ArrayList<>(transactions));
    }

    private String buildTree(List<String> nodes) {
        // 复制以避免修改原列表
        List<String> currentLevel = new ArrayList<>(nodes);

        while (currentLevel.size() > 1) {
            List<String> nextLevel = new ArrayList<>();
            int i = 0;
            while (i < currentLevel.size()) {
                String left = currentLevel.get(i);
                i++;

                // 处理奇数个节点的情况：复制最后一个节点
                String right = (i < currentLevel.size()) ? currentLevel.get(i) : left;
                i++;

                // 按字典序排序防御前缀攻击
                String[] sorted = {left, right};
                java.util.Arrays.sort(sorted);
                String combined = sorted[0] + sorted[1];

                nextLevel.add(hash(combined));
            }
            currentLevel = nextLevel;
        }
        return currentLevel.get(0);
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes("UTF-8"));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }

    public String getRoot() {
        return root;
    }

    // 验证交易存在性的方法
    public boolean containsTransaction(String tx, List<String> proof) {
        String computedHash = hash(tx);
        for (String proofNode : proof) {
            String[] parts = proofNode.split(":");
            if (parts[0].equals("L")) {
                computedHash = hash(parts[1] + computedHash);
            } else {
                computedHash = hash(computedHash + parts[1]);
            }
        }
        return computedHash.equals(root);
    }
}