package com.example.network;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.model.AllNodeInfo;
import com.example.model.Node;
import com.example.model.PBFT.BaseMessage;
import com.example.model.PBFT.Message;
import com.example.model.PBFT.NewView;
import com.example.model.PBFT.PBFTMessage;
import com.example.model.PBFTInfo;
import com.example.util.*;
import it.unisa.dia.gas.jpbc.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class Common {

    @Autowired
    private Node node;

    @Autowired
    private AppServer appServer;

    @Autowired
    private BlockCache blockCache;

    private static final String path="src/main/resources/certs/";

    private static final Logger logger = LoggerFactory.getLogger(Common.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private volatile ScheduledFuture<?> timeoutHandler = null;

    public static String index = null;

    // 选举信号量机制
    private final Object electionLock = new Object();

    private volatile boolean electionOngoing = false;

    // 新增成员变量
    private volatile ScheduledFuture<?> replicaTimeoutHandler = null;

    public void listener() {
        new Thread(() -> {
            while (true) {
                if (AllNodeInfo.nodes.size() == 3 * node.getF() + 1) {
                    handleElection();
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    logger.warn("休眠异常{}", e.getMessage());
                    Thread.currentThread().interrupt(); // 重置中断状态
                    break; // 发生中断时退出循环
                }
            }
        }).start();
    }

    public void handleElection() {
        // 检查是否已经在选举中
        synchronized (electionLock) {
            if (electionOngoing) {
                System.out.println("选举正在进行中，跳过本次选举触发");
                return;
            }
            electionOngoing = true;
        }

        int currentView = node.getView();
        int newView = currentView + 1;

        // 检查是否已经在该新视图中发送过viewchange消息
        if (PBFTInfo.viewChangeMessages.containsKey(newView) &&
                PBFTInfo.viewChangeMessages.get(newView).containsKey(node.getIndex())) {
            System.out.println("已在新视图 " + newView + " 发送过ViewChange消息，不再触发选举");
            synchronized (electionLock) {
                electionOngoing = false;
            }
            return;
        }
        logger.info("当前随机值{}，消息{}", PBFTInfo.values.size(), PBFTInfo.viewChangeMessages.size());
        String hash = blockCache.getLatestBlock().getHash();
        //<<data,n,v,i>pk,d,sig>
        PBFTMessage message = new PBFTMessage();
        BaseMessage msg=new BaseMessage();

//        Element privateKey = BLS.PemToSk(node.getPrivateKey());
        Element privateKey=Util.readPrivateKey(path+node.getIndex()+"/node_sk.key");
//        Element publicKey = BLS.PemToPk(node.getPublicKey());
        X509Certificate certificate=Util.parseCertificate(path+node.getIndex()+"/node.cer");
        Element publicKey=Util.getCertPublicKey(certificate);

        String data = VRF.getProof(hash, publicKey, privateKey, node.getIndex());

//        message.setData(data);
//        message.setSequence(node.getSequence() + 1);
//        message.setView(newView); // 设置为新视图
//        message.setPublickey(BLS.PkToPem(publicKey));
//        message.setType(BlockConstant.VIEWCHANGE);
//        message.setNodeIndex(node.getIndex());
        msg.setData(data);
        msg.setSequence(node.getSequence());
        msg.setView(newView);
        msg.setNodeIndex(node.getIndex());

        message.setBase_message(msg);
//        message.setPublickey(BLS.PkToPem(publicKey));
        String digest = CryptoUtil.SHA256(msg.toString());
        message.setDigest(digest);
        Element signature = BLS.sign(digest, privateKey);
        if (signature != null)
            message.setSignature(BLS.SigToPem(signature));

        PBFTInfo.addViewChange(message);
        VRF.verify(hash, publicKey, data, node.getIndex());
        broadcast(message, "View_Change");
        startViewChangeTimoutTimer();
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

    // 发送消息到指定连接
    private static boolean sendMessage(SocketChannel channel, String msg) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
            channel.write(buffer);
            buffer.clear();
            return true;
        } catch (IOException e) {
            System.err.println("发送消息失败: " + e.getMessage());
            close(channel);
            return false;
        }
    }

    // 在Common类的sendMessage方法中修改close方法：
    private static void close(SocketChannel channel) {
        try {
            String channelKey = findChannelKey(channel);
            if (channelKey != null) {
                AllNodeInfo.channels.remove(channelKey);
                System.out.println("移除通道: " + channelKey);
            }
            channel.close();
        } catch (Exception e) {
            System.out.println("关闭连接异常: " + e.getMessage());
        }
    }

    // 根据通道查找对应的键
    private static String findChannelKey(SocketChannel targetChannel) {
        for (Map.Entry<String, SocketChannel> entry : AllNodeInfo.channels.entrySet()) {
            if (entry.getValue().equals(targetChannel))
                return entry.getKey();
        }
        return null;
    }

    private void cancelTimeoutTimer() {
        if (timeoutHandler != null) {
            timeoutHandler.cancel(false);
            timeoutHandler = null;
        }
    }

    private void startViewChangeTimoutTimer() {
        cancelTimeoutTimer();
        timeoutHandler = scheduler.schedule(() -> {
            synchronized (this) {
                try {
                    int newView = node.getView() + 1; // 当前触发的是视图切换到newView
                    int count = PBFTInfo.values.size();//保存的消息值
                    logger.info("视图选举等待时间结束，收到{}/{}", count, AllNodeInfo.nodes.size());
                    if (count > 2 * node.getF()) {//收到2f+1的消息才有效
                        index = VRF.getMinIndex(PBFTInfo.values);
                        // 更新节点视图到新的视图
                        if (index != null && index.equals(node.getIndex()))
                            node.setIsPrimary(true);
                        else {
                            node.setIsPrimary(false);
                            startReplicaNewViewTimeoutTimer(newView);
                        }
                        logger.info("主节点{}", index);
                        if (node.getIsPrimary()) {//主节点
//                            try{
//                                Thread.sleep(5000);
//                            }catch (Exception e){
//                                logger.error("休眠失败{}",e.getMessage());
//                            }
                            node.setView(newView);//设置新视图
                            Map<String, PBFTMessage> view_changes = PBFTInfo.getViewChangeMessages(newView);//获取日志中的消息
                            List<Element> signatures = new ArrayList<>();
                            List<BaseMessage> messages = new ArrayList<>();
//                            List<String> publicKeys = new ArrayList<>();
                            List<String> nodes=new ArrayList<>();
                            for (Map.Entry<String, PBFTMessage> entry : view_changes.entrySet()) {
                                PBFTMessage message = entry.getValue();
                                signatures.add(BLS.PemToSig(message.getSignature()));
//                                publicKeys.add(message.getPublickey());
                                nodes.add(message.getBase_message().getNodeIndex());
                                messages.add(message.getBase_message());
                            }
                            //<pks,agg_sig,messages>
                            PBFTMessage message = new PBFTMessage();
                            BaseMessage msg = new BaseMessage();
                            NewView new_view=new NewView();
                            Element agg_sig = BLS.AggregateSignatures(signatures);

                            //基础信息
                            msg.setType(BlockConstant.NEWVIEW);
                            msg.setNodeIndex(node.getIndex());
                            msg.setView(newView);
                            msg.setSequence(node.getSequence());

                            new_view.setViewChanges(messages);//签名消息
//                            new_view.setPublicKeys(publicKeys);//公钥集合
                            new_view.setNodes(nodes);
//                            message.setNodes(nodes);
                            new_view.setAgg_signature(BLS.SigToPem(agg_sig));//聚合签名

                            message.setBase_message(msg);
                            String digest = CryptoUtil.SHA256(msg.toString());
                            message.setDigest(digest);

//                            Element sk = BLS.PemToSk(node.getPrivateKey());
                            Element sk=Util.readPrivateKey(path+node.getIndex()+"/node_sk.key");
                            Element sign = BLS.sign(digest, sk);
                            message.setSignature(BLS.SigToPem(sign));
//                            message.setPublickey(node.getPublicKey());
                            message.setAgg_viewChange(new_view);
                            broadcast(message, "New_View");
                            PBFTInfo.clearValues();
                            PBFTInfo.viewChangeMessages.clear();
                            appServer.resendPendingRequests();
                        }
//                        node.setView(newView);
//
//                        logger.info("更新当前视图{}清空消息", newView);
//                        PBFTInfo.values.clear();
//                        PBFTInfo.viewChangeMessages.clear();
                    } else {
                        logger.warn("未收到足够的视图切换消息，继续等待");
                    }
                } finally {
                    // 无论选举成功与否，都释放选举锁
                    synchronized (electionLock) {
                        electionOngoing = false;
                        electionLock.notifyAll();
                    }
                }
            }
        }, 3, TimeUnit.SECONDS);
    }

    // 新增方法：副本节点启动NewView超时检测
    private void startReplicaNewViewTimeoutTimer(int expectedView) {
        logger.info("启动new-view超时计时器");
        if (node.getIsPrimary()) return; // 主节点无需处理

        cancelReplicaTimeoutTimer(); // 取消旧计时器
        replicaTimeoutHandler = scheduler.schedule(() -> {
            synchronized (this) {
                if (node.getView() != expectedView) {
                    logger.warn("超时，未收到NewView消息，重新触发选举");
                    PBFTInfo.clearValues();
                    PBFTInfo.viewChangeMessages.clear();
                    handleElection();
                }
            }
        }, 5, TimeUnit.SECONDS); // 超时时间设为5秒
    }

    // 新增方法：取消副本超时计时器
    private void cancelReplicaTimeoutTimer() {
        if (replicaTimeoutHandler != null) {
            logger.info("取消new-view超时计时器");
            replicaTimeoutHandler.cancel(false);
            replicaTimeoutHandler = null;
        }
    }

    public synchronized void updateStatus(int view,String index){
        node.setView(view);
        Common.index=index;
        PBFTInfo.clearValues();
        PBFTInfo.viewChangeMessages.clear();
        cancelReplicaTimeoutTimer();
        logger.info("更新视图{},主节点{}",view,Common.index);
        appServer.resendPendingRequests();
    }
    // 检查选举状态
    public boolean isElectionOngoing() {
        synchronized (electionLock) {
            return electionOngoing;
        }
    }

    // 等待选举完成
    public void waitForElectionCompletion() throws InterruptedException {
        synchronized (electionLock) {
            while (electionOngoing) {
                electionLock.wait();
            }
        }
    }
}
