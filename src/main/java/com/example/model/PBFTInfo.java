package com.example.model;

import com.example.model.PBFT.Message;
import com.example.model.PBFT.PBFTMessage;
import com.example.model.client.ClientRequest;
import it.unisa.dia.gas.jpbc.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class PBFTInfo {
    // 节点索引和随机值
    public static final Map<String, String> values = new ConcurrentHashMap<>();
    //按视图号组织
    public static final Map<Integer, Map<String, PBFTMessage>> viewChangeMessages = new ConcurrentHashMap<>();
    // 预准备消息-按视图和序号组织
    private static final Map<Integer, Map<Integer, Message>> pre_prepareMessages = new ConcurrentHashMap<>();
    // 取出消息的区块
    private static final Map<Integer,Block> blocks=new ConcurrentHashMap<>();
    // 准备消息 - 按视图和序列号组织
    private static final Map<Integer, Map<Integer, Map<String, PBFTMessage>>> prepareMessages = new ConcurrentHashMap<>();
    // 按视图和序列号组织Commit消息
    private static final Map<Integer, Map<Integer, Map<String, PBFTMessage>>> commitMessages = new ConcurrentHashMap<>();
    // 准备后消息 - 按视图和序列号组织
    private static final Map<Integer, Map<Integer, Map<String, PBFTMessage>>> prepare_After_Messages = new ConcurrentHashMap<>();
    // 按视图和序列号组织Commit_After消息
    private static final Map<Integer, Map<Integer, Map<String, PBFTMessage>>> commit_After_Messages = new ConcurrentHashMap<>();

    // 保存视图切换消息
    public static void addViewChange(PBFTMessage message) {
        viewChangeMessages
                .computeIfAbsent(message.getBase_message().getView(), k -> new ConcurrentHashMap<>())
                .put(message.getBase_message().getNodeIndex(), message);
    }

    public static Map<String, PBFTMessage> getViewChangeMessages(int view) {
        return viewChangeMessages.getOrDefault(view, new ConcurrentHashMap<>());
    }

    // 在PBFTInfo类中添加方法
    public static int getViewChangeCountForView(int view) {
        return viewChangeMessages.getOrDefault(view, new ConcurrentHashMap<>()).size();
    }

    // 添加pre-prepare消息
    public static void addPrePrepare(PBFTMessage msg) {
        pre_prepareMessages
                .computeIfAbsent(msg.getBase_message().getView(), v -> new ConcurrentHashMap<>())
                .put(msg.getBase_message().getSequence(), msg.getAgg_message());
    }

    // 获取指定视图和序列号的pre-prepare消息集合
    public static Message getPrePreparesForViewAndSequence(int view, int sequence) {
        return pre_prepareMessages.getOrDefault(view, Collections.emptyMap())
                .get(sequence);
    }

    public static void addPrepare(PBFTMessage msg) {
        prepareMessages.computeIfAbsent(msg.getBase_message().getView(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(msg.getBase_message().getSequence(), k -> new ConcurrentHashMap<>())
                .put(msg.getBase_message().getNodeIndex(), msg);
    }

    public static Map<String, PBFTMessage> getPreparesForViewAndSequence(int view, int sequence) {
        return prepareMessages.getOrDefault(view, new ConcurrentHashMap<>())
                .getOrDefault(sequence, new ConcurrentHashMap<>());
    }

    public static void addPrepare_After(PBFTMessage msg) {
        prepare_After_Messages.computeIfAbsent(msg.getBase_message().getView(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(msg.getBase_message().getSequence(), k -> new ConcurrentHashMap<>())
                .put(msg.getBase_message().getNodeIndex(), msg);
    }

    public static Map<String, PBFTMessage> getPrePareAfterForViewAndSequence(int view, int sequence) {
        return prepare_After_Messages.getOrDefault(view, new ConcurrentHashMap<>())
                .getOrDefault(sequence, new ConcurrentHashMap<>());
    }

    public static void addCommit(PBFTMessage message) {
        commitMessages.computeIfAbsent(message.getBase_message().getView(), v -> new ConcurrentHashMap<>())
                .computeIfAbsent(message.getBase_message().getSequence(), s -> new ConcurrentHashMap<>())
                .put(message.getBase_message().getNodeIndex(), message);
    }

    public static Map<String, PBFTMessage> getCommitsForViewAndSequence(int view, int sequence) {
        return commitMessages.getOrDefault(view, new ConcurrentHashMap<>())
                .getOrDefault(sequence, new ConcurrentHashMap<>());
    }

    public static void addCommit_After(PBFTMessage msg) {
        commit_After_Messages.computeIfAbsent(msg.getBase_message().getView(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(msg.getBase_message().getSequence(), k -> new ConcurrentHashMap<>())
                .put(msg.getBase_message().getNodeIndex(), msg);
    }

    public static Map<String, PBFTMessage> getCommitAfterForViewAndSequence(int view, int sequence) {
        return commit_After_Messages.getOrDefault(view, new ConcurrentHashMap<>())
                .getOrDefault(sequence, new ConcurrentHashMap<>());
    }

    public static int getTotalCommitAfterMessagesCount() {
        int total = 0;
        // 遍历所有视图
        for (Map<Integer, Map<String, PBFTMessage>> viewMap : commit_After_Messages.values()) {
            // 遍历每个视图下的所有序列号
            for (Map<String, PBFTMessage> sequenceMap : viewMap.values()) {
                // 累加每个序列号对应的节点消息数量
                total += sequenceMap.size();
            }
        }
        return total;
    }

    // 随机值操作方法
    public static void setValue(String index, String value) { values.put(index, value); }

    public static String getValue(String index) {
        return values.get(index);
    }

    public static void clearValues() {
        values.clear();
    }

    // 随机值操作方法
    public static void setBlock(Integer view, Block block) {
        blocks.put(view, block);
    }
    // 随机值操作方法
    public static Block getBlock(Integer view) {
        return blocks.get(view);
    }
    public static void clearBlock() {
        blocks.clear();
    }

    public static int prepareCountForView(Integer view) {
        return prepareMessages.getOrDefault(view, new ConcurrentHashMap<>()).size();
    }

    public static int commitCountForView(Integer view) {
        return commitMessages.getOrDefault(view, new ConcurrentHashMap<>()).size();
    }
    // 清空方法
    public static synchronized void clearForView(Integer view) {
        prepareMessages.remove(view);
        commitMessages.remove(view);
    }

    public static void clearPrePrepareMessages() {
        pre_prepareMessages.clear();
    }

    public static synchronized void clearAll() {
        prepareMessages.clear();
        pre_prepareMessages.clear();
        commitMessages.clear();
        commit_After_Messages.clear();
        blocks.clear();
    }

    public static void printStatus() {
        System.out.println("====== 日志状态 ======");

        // 打印预准备消息统计
        pre_prepareMessages.forEach((view, seqMap) -> {
            System.out.printf("视图 %d: %d 个预准备消息\n", view, seqMap.size());
            seqMap.forEach((seq, msg) ->
                    System.out.printf("  序列号 %d: %s\n", seq, msg.getClass().getSimpleName())
            );
        });

        // 打印准备消息统计
        printMessageStatus("准备", prepareMessages);

        // 打印聚合准备消息统计
        printMessageStatus("聚合准备", prepare_After_Messages);

        // 打印提交消息统计
        printMessageStatus("提交", commitMessages);

        // 打印聚合提交消息统计
        printMessageStatus("聚合提交", commit_After_Messages);

        System.out.println("=====================");
    }

    // 提取通用的消息统计打印逻辑
    private static void printMessageStatus(String msgType,
                                           Map<Integer, Map<Integer, Map<String, PBFTMessage>>> messageMap) {
        messageMap.forEach((view, seqMap) -> {
            System.out.printf("视图 %d: %d 个%s消息\n", view, seqMap.size(), msgType);
            seqMap.forEach((seq, nodeMap) ->
                    System.out.printf("  序列号 %d: %d 个节点消息\n", seq, nodeMap.size())
            );
        });
    }
}