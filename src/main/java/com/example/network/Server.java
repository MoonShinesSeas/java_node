package com.example.network;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.model.AllNodeInfo;
import com.example.model.Block;
import com.example.model.Node;
import com.example.model.PBFT.BaseMessage;
import com.example.model.PBFT.PBFTMessage;
import com.example.model.PBFTInfo;
import com.example.service.BlockService;
import com.example.util.*;
import it.unisa.dia.gas.jpbc.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

@Component
public class Server {
    @Autowired
    private Node node;

    @Autowired
    private BlockService blockService;

    @Lazy
    @Autowired
    private AppServer appServer;

    private final static String path = "src/main/resources/certs/";

    private final int size = 1024 * 4096;

    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private final Map<String, String> consensusPhase = new ConcurrentHashMap<>();

    // 生成状态键：视图+序列号
    private String getPhaseKey(int view, int sequence) {
        return view + "-" + sequence;
    }

    public void start() {
        Thread thread = new Thread(new connection());
        thread.start();
    }

    // 新建线程保持连接，期间主程序去完成其他任务
    private class connection implements Runnable {
        @Override
        public void run() {
            try {
                Selector selector = Selector.open();
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

                serverSocketChannel.socket().bind(new InetSocketAddress(node.getPort()));
                serverSocketChannel.configureBlocking(false);
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

                String localIp = CommonUtil.getLocalIp();
                logger.info("服务端启动，监听{}:{}", localIp, node.getApp_port());

                while (true) {
                    selector.select();
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();

                        if (key.isAcceptable()) {
                            handleAccept(key, selector);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        }
                        keyIterator.remove();
                    }
                }
            } catch (IOException e) {
                logger.warn("服务端，监听异常{}", e.getMessage());
            }
        }
    }

    // 接收到连接
    private void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverchannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverchannel.accept();
        clientChannel.configureBlocking(false);
        clientChannel.register(selector, SelectionKey.OP_READ);
        // 保存通道 :client表示这是连接到我的通道，广播使用
        AllNodeInfo.setChannels(clientChannel.getRemoteAddress().toString() + ":client", clientChannel);
        System.out.println(clientChannel.getRemoteAddress() + "已连接");
    }

    // 接收到信息
    private void handleRead(SelectionKey key) {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            ByteBuffer buffer = ByteBuffer.allocate(size);
            int bytesRead = socketChannel.read(buffer);

            if (bytesRead > 0) {
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                buffer.clear();
                String message = new String(data);
                handleMessage(message, socketChannel);

            } else if (bytesRead == -1) {
                ChannelUtil.close(socketChannel);
            }
        } catch (Exception e) {
            ChannelUtil.close(socketChannel);
        }
    }

    // 发送消息到指定连接
    private static boolean sendMessage(SocketChannel channel, String msg) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
            channel.write(buffer);
            buffer.clear();
            return true;
        } catch (IOException e) {
            System.err.println("发送消息失败: " + e.getMessage());
            ChannelUtil.close(channel);
            return false;
        }
    }

    // 处理接收到的消息
    private void handleMessage(String msg, SocketChannel channel) {
        PBFTMessage message = JSON.parseObject(msg, PBFTMessage.class);
        logger.info("处理{}的{}消息", message.getBase_message().getNodeIndex(), message.getBase_message().getType());
        switch (message.getBase_message().getType()) {
            case BlockConstant.TEST:
                BaseMessage base_msg = new BaseMessage();
                base_msg.setType(BlockConstant.LIFE);
                PBFTMessage life = new PBFTMessage();
                life.setBase_message(base_msg);
                sendMessage(channel, JSON.toJSONString(life));
                break;
            case BlockConstant.PREPARE:
                handlePrePare(message);
                break;
            case BlockConstant.COMMIT:
                hanleCommit(message);
                break;
            default:
                System.out.println("SERVER-未知类型" + message.getBase_message().getType());
                break;
        }
    }

    public boolean checkMessage(PBFTMessage pbftMessage) {
        String nodeIndex = pbftMessage.getBase_message().getNodeIndex();
        X509Certificate client_cert = Util.parseCertificate(path + nodeIndex + "/node.cer");
        Element pk = Util.getCertPublicKey(client_cert);
//        Element pk = BLS.PemToPk(pbftMessage.getPublickey());
        Element sig = BLS.PemToSig(pbftMessage.getSignature());
        if (!BLS.verify(sig, pk, pbftMessage.getDigest())) {
            System.out.println("签名不匹配，开始视图切换");
            return false;
        }
        if (pbftMessage.getBase_message().getView() != node.getView()) {
            System.out.println("视图不匹配，开始视图切换");
            return false;
        }
        if (pbftMessage.getBase_message().getSequence() != node.getSequence()) {
            System.out.println("序列号不匹配，开始视图切换");
            return false;
        }
        return true;
    }

    // 发送完pre_prepare消息等待足够时间后进行聚合
    public synchronized void onPre_PrePare(PBFTMessage pbftMessage) {
        PBFTInfo.addPrePrepare(pbftMessage);//确保日志一致
        String key = getPhaseKey(pbftMessage.getBase_message().getView(), pbftMessage.getBase_message().getSequence() - 1);
        if ("Reply".equals(consensusPhase.get(key)) || consensusPhase.get(key) == null) {
            consensusPhase.remove(key);//上一个阶段结束
            key = getPhaseKey(node.getView(), node.getSequence());
            consensusPhase.put(key, "Pre_Prepare");//下一个阶段开始
        } else
            logger.warn("{}消息正在共识", node.getSequence());
        broadcast(pbftMessage, "Pre_Prepare");// 向其他副本节点广播消息
//        String pre_preparekey = "pre_prepare" + pbftMessage.getBase_message().getView() + "-" + pbftMessage.getBase_message().getSequence();
//         等待prepare消息的时间
//        ScheduledFuture<?> pre_prepareTimeout = pre_prepareScheduler.schedule(() -> {
//            logger.warn("pre_prepare时间到，进入prepare_after阶段");
//            brocastPrePare_After();
//            pre_prepareTimeoutMap.remove(pre_preparekey);
//        }, 2, TimeUnit.SECONDS);
//        pre_prepareTimeoutMap.put(pre_preparekey, pre_prepareTimeout);
    }

    // 主节点处理准备消息
    public void handlePrePare(PBFTMessage message) {
        if (message.getBase_message().getView() < node.getView()) {
            logger.warn("收到过期的Prepare消息 view={} 当前view={}", message.getBase_message().getView(), node.getView());
            return; // 直接丢弃旧视图消息
        }
        String key = getPhaseKey(node.getView(), node.getSequence());
        //在Pre_PrePare阶段
        if ("Pre_Prepare".equals(consensusPhase.get(key))) {
            if (!checkMessage(message))
                return;
            if (!node.getIsPrimary()) {
                System.out.println("非主节点忽略prepare消息");
                return;
            }
            PBFTInfo.addPrepare(message);
            if (!(PBFTInfo.getPreparesForViewAndSequence(node.getView(), node.getSequence()).size() < 2 * node.getF())) {
                brocastPrePare_After();
            }
        } else {
            logger.warn("收到过期的Prepare消息 共识状态{} 当前{}", message.getBase_message().getType(), consensusPhase.get(key));
        }
    }

    // 广播聚合后的prepare消息
    private void brocastPrePare_After() {
        String key = getPhaseKey(node.getView(), node.getSequence());
        if ("Pre_Prepare".equals(consensusPhase.get(key)))
            consensusPhase.put(key, "Prepare_After");
        List<Element> sigs = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
//        List<Element> pks = new ArrayList<>();
        // 使用 getPreparesForView 方法
        Map<String, PBFTMessage> prepares = PBFTInfo.getPreparesForViewAndSequence(node.getView(), node.getSequence());

        PBFTMessage prepare_after_message = new PBFTMessage();
        BaseMessage msg = new BaseMessage();
        msg.setType(BlockConstant.PREPARE_AFTER);
        msg.setSequence(node.getSequence());
        msg.setView(node.getView());
        msg.setNodeIndex(node.getIndex());
        prepare_after_message.setBase_message(msg);
//        prepare_after_message.setType(BlockConstant.PREPARE_AFTER);
//        prepare_after_message.setNodeIndex(node.getIndex());
        prepares.values().forEach(sig_msg -> {
            sigs.add(BLS.PemToSig(sig_msg.getSignature()));
//            pks.add(BLS.PemToPk(sig_msg.getPublickey()));
//            X509Certificate cert = Util.parseCertificate(path + sig_msg.getBase_message().getNodeIndex() + "/node.cer");
//            pks.add(Util.getCertPublicKey(cert));
            //prepare_after_message.setBlock(msg.getBlock());//不附加区块
            nodes.add(sig_msg.getBase_message().getNodeIndex());
            prepare_after_message.setDigest(sig_msg.getDigest());
        });

        String sig = BLS.SigToPem(BLS.AggregateSignatures(sigs));
//        String pk = BLS.PkToPem(BLS.AggregatePublicKey(pks));
        prepare_after_message.setSignature(sig);
        prepare_after_message.setNodes(nodes);
//        prepare_after_message.setPublickey(pk);
//        prepare_after_message.setView(node.getView());// 视图号
//        prepare_after_message.setSequence(node.getSequence());// 消息序列号
        broadcast(prepare_after_message, "Prepare_After");
        // 启动commit超时计时,开始等待副本节点Commit
//        String prepare_key = "prepare_after" + prepare_after_message.getView() + "-" + prepare_after_message.getSequence();
        // 等待prepare消息的时间
        PBFTInfo.addPrepare_After(prepare_after_message);
    }

    public void hanleCommit(PBFTMessage message) {
        if (message.getBase_message().getView() < node.getView()) {
            logger.warn("收到过期的Commit消息 view={} 当前view={}", message.getBase_message().getView(), node.getView());
            return; // 直接丢弃旧视图消息
        }
        String key = getPhaseKey(message.getBase_message().getView(), message.getBase_message().getSequence());
        //在Pre_PrePare阶段
        if ("Prepare_After".equals(consensusPhase.get(key))) {
            if (!checkMessage(message))
                return;
            if (!node.getIsPrimary())
                return;
            PBFTInfo.addCommit(message);
            if (!(PBFTInfo.getCommitsForViewAndSequence(node.getView(), node.getSequence()).size() < 2 * node.getF())) {
                broadcastCommit_After();
            }
        }
    }

    // 广播聚合后的commit消息
    private void broadcastCommit_After() {
        String key = getPhaseKey(node.getView(), node.getSequence());
        if ("Prepare_After".equals(consensusPhase.get(key)))
            consensusPhase.put(key, "Commit_After");

        List<Element> sigs = new ArrayList<>();
//        List<Element> pks = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        // 使用 getPreparesForView 方法
        Map<String, PBFTMessage> commits = PBFTInfo.getCommitsForViewAndSequence(node.getView(), node.getSequence());
        if (commits.size() < 2 * node.getF())
            return;
        PBFTMessage commit_after = new PBFTMessage();
        BaseMessage msg = new BaseMessage();
        msg.setType(BlockConstant.COMMIT_AFTER);
        msg.setSequence(node.getSequence());
        msg.setView(node.getView());
        msg.setNodeIndex(node.getIndex());
        commit_after.setBase_message(msg);
//        commit_after.setType(BlockConstant.COMMIT_AFTER);
//        commit_after.setNodeIndex(node.getIndex());

        commits.values().forEach(sign_msg -> {
            sigs.add(BLS.PemToSig(sign_msg.getSignature()));
//            pks.add(BLS.PemToPk(sign_msg.getPublickey()));
            commit_after.setDigest(sign_msg.getDigest());
            nodes.add(sign_msg.getBase_message().getNodeIndex());
//            commit_after.setBlock(msg.getBlock());
        });
        String sig = BLS.SigToPem(BLS.AggregateSignatures(sigs));
//        String pk = BLS.PkToPem(BLS.AggregatePublicKey(pks));
        commit_after.setSignature(sig);
        commit_after.setNodes(nodes);
//        commit_after.setPublickey(pk);
//        commit_after.setSequence(node.getSequence());// 消息序列号
//        commit_after.setView(node.getView());// 视图号

        broadcast(commit_after, "Commit_After");
        PBFTInfo.addCommit_After(commit_after);
        // 执行返回
        // Block block = message.getBlock();

        Block block = PBFTInfo.getBlock(node.getView());
//        logger.info("区块信息{}",JSON.toJSONString(block));
//        blockService.addBlock(block);
//        for(Request request:block.getBlockInfo()){
//            JSONObject blockInfo=(JSONObject) JSON.toJSON(request);
//            BlockUtil.parseBlockInfo(block.getHash(), blockInfo);
//        }
        PBFTInfo.clearPrePrepareMessages();
        PBFTInfo.clearAll();//清空日志
        if ("Commit_After".equals(consensusPhase.get(key)))
            consensusPhase.put(key, "Reply");

        node.setSequence(commit_after.getBase_message().getSequence() + 1);
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
            System.out.println("休眠1s" + e.getMessage());
        }
        PBFTMessage reply = new PBFTMessage();
        BaseMessage reply_msg = new BaseMessage();
        reply_msg.setBlock(block);
        reply.setBase_message(reply_msg);
        appServer.responseClient(reply);
        appServer.signalConsensusCompletion();
        logger.info("保存区块,共识结束");
    }

    /**
     * 向所有连接的节点广播消息
     */
    public static void broadcast(PBFTMessage message, String type) {
        if (AllNodeInfo.channels.isEmpty()) {
            System.out.println("没有连接");
            return;
        }
        AllNodeInfo.printChannels();
        String msg = JSON.toJSONString(message, SerializerFeature.DisableCircularReferenceDetect);
        logger.info("广播{}消息开始", type);
        logger.info(msg);
        // 广播节点
        AllNodeInfo.channels.forEach((nodeKey, channel) -> {
            String[] parts = nodeKey.split(":");
            if (channel.isConnected() && parts[2].equals("client"))
                sendMessage(channel, msg);
        });
        // 广播到主动连接的节点
        logger.info("广播{}消息结束", type);
    }

}
