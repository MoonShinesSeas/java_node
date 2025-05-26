package com.example.network;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.model.*;
import com.example.model.PBFT.BaseMessage;
import com.example.model.PBFT.Message;
import com.example.model.PBFT.NewView;
import com.example.model.PBFT.PBFTMessage;
import com.example.model.client.ClientRequest;
import com.example.model.client.Request;
import com.example.model.connect.ConnectMessage;
import com.example.service.BlockService;
import com.example.util.*;
import it.unisa.dia.gas.jpbc.Element;
import org.bouncycastle.jcajce.provider.asymmetric.X509;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class Client {
    @Autowired
    private Node node;

    @Autowired
    private Common common;

    @Autowired
    private BlockCache blockCache;

    @Autowired
    private AppServer appServer;

    @Autowired
    private BlockService blockService;

    private final static String path = "src/main/resources/certs/";

    private final static int size = 1024 * 4096;

    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    private final ScheduledExecutorService viewChangeScheduler = Executors.newScheduledThreadPool(1);

    private volatile ScheduledFuture<?> viewChangeTimeoutHandler = null;

    private final ScheduledExecutorService prepareScheduler = Executors.newScheduledThreadPool(1);
    // 新增成员变量：管理Prepare阶段的计时器
    private final ConcurrentMap<String, ScheduledFuture<?>> prepareTimeoutMap = new ConcurrentHashMap<>();
    // 新增成员变量
    private final ScheduledExecutorService commitScheduler = Executors.newScheduledThreadPool(1);

    private final ConcurrentMap<String, ScheduledFuture<?>> commitTimeoutMap = new ConcurrentHashMap<>();

    private final Map<String, String> consensusPhase = new ConcurrentHashMap<>();

    // 生成状态键：视图+序列号
    private String getPhaseKey(int view, int sequence) {
        return view + "-" + sequence;
    }

    // 启动一个线程管理连接到的服务器
    public void start(String server, int port) {
        // 创建并启动管理连接的线程
        new Thread(() -> {
            try {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false); // 设置为非阻塞模式
                logger.info("客户端启动，连接{}:{}", server, port);
                channel.connect(new InetSocketAddress(server, port));
                // 如果连接需要时间，等待连接完成
                while (!channel.finishConnect()) {
                    logger.info("等待连接...");
                    Thread.sleep(100);
                }
                logger.info("连接{}:{}成功", server, port);
                // ：server表示这是连接到服务器的通道
                AllNodeInfo.setChannels("/" + server + ":" + port + ":server", channel);
                // 接收消息循环
                receive(server, channel, false);

            } catch (IOException | InterruptedException e) {
                logger.error("连接异常{}", e.getMessage());
            }
        }).start();
    }

    // 连接到锚节点进行节点发现
    public void ConnectToAnchor(String server, int port) {
        // 创建并启动管理连接的线程
        new Thread(() -> {
            try {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false); // 设置为非阻塞模式
                logger.info("客户端启动，连接锚节点{}:{}", server, port);
                // 绑定客户端本地端口
                if (channel.connect(new InetSocketAddress(server, port))) {
                    String data = node.getIp() + ":" + node.getPort();
                    ConnectMessage connMessage = new ConnectMessage();
                    connMessage.setData(data);
                    connMessage.setNodeIndex(node.getIndex().toString());
                    sendMessage(channel, JSON.toJSONString(connMessage));
                } else {
                    // 如果连接需要时间，等待连接完成
                    while (!channel.finishConnect()) {
                        logger.info("等待连接...");
                        Thread.sleep(100);
                    }
                    String data = node.getIp() + ":" + node.getPort();
                    ConnectMessage connMessage = new ConnectMessage();
                    connMessage.setData(data);
                    connMessage.setNodeIndex(node.getIndex());
                    sendMessage(channel, JSON.toJSONString(connMessage));
                }
                // 接收消息循环
                receive(server, channel, true);

            } catch (IOException | InterruptedException e) {
                logger.error("连接锚节点异常{}", e.getMessage());
            }
        }).start();
    }

    // 锚节点消息处理器
    private void handleAnchorMessage(String message) {
        ConnectMessage conn = JSON.parseObject(message, ConnectMessage.class);
        String data = conn.getData();
        Map<String, NodeInfo> nodeInfoMap = JSON.parseObject(data, new TypeReference<Map<String, NodeInfo>>() {
        });
        for (Map.Entry<String, NodeInfo> entry : nodeInfoMap.entrySet()) {
            String nodeIndex = entry.getKey();
            NodeInfo nodeInfo = entry.getValue();
            AllNodeInfo.setNodes(nodeIndex, nodeInfo);
            if (!(node.getIp().equals(nodeInfo.getIP()) && node.getPort() == nodeInfo.getPort())) {
                if (!(AllNodeInfo.channels
                        .containsKey("/" + nodeInfo.getIP() + ":" + nodeInfo.getPort() + ":server")))
                    start(nodeInfo.getIP(), nodeInfo.getPort());//跳转到连接函数
            }
        }
        AllNodeInfo.printNodes();
    }

    private void receive(String server, SocketChannel channel, boolean isAnchorChannel) {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        try {
            while (channel.isConnected()) {
                int bytesRead = channel.read(buffer);

                if (bytesRead > 0) {
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    buffer.clear();
                    String response = new String(data);
                    if (isAnchorChannel)
                        handleAnchorMessage(response);
                    else
                        handleMessage(response, channel);
                } else if (bytesRead == -1) {
                    logger.warn("{}连接已关闭", server);
                    ChannelUtil.close(channel);
                    break;
                }
                // 非阻塞模式下，没有数据时短暂休眠
                Thread.sleep(50);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("接收消息异常{}", e.getMessage());
        } finally {
            ChannelUtil.close(channel);
        }
    }

    // 发送消息到指定连接
    private boolean sendMessage(SocketChannel channel, String msg) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
            logger.info("发送消息{}", msg);
            channel.write(buffer);
            buffer.clear();
            return true;
        } catch (IOException e) {
            System.err.println("发送消息失败: " + e.getMessage());
            ChannelUtil.close(channel);
            return false;
        }
    }

    private void handleMessage(String msg, SocketChannel channel) {
        PBFTMessage message = JSON.parseObject(msg, PBFTMessage.class);
        logger.info("处理{}的{}消息", message.getBase_message().getNodeIndex(), message.getBase_message().getType());
        switch (message.getBase_message().getType()) {
            case BlockConstant.NEWVIEW:
                handleNewView(message);
                break;
            case BlockConstant.PRE_PREPARE:
                handlePre_PrePare(message, channel);
                break;
            case BlockConstant.VIEWCHANGE:
                handleViewChange(message);
                break;
            case BlockConstant.PREPARE_AFTER:
                handlePrePare_After(message, channel);
                break;
            case BlockConstant.COMMIT_AFTER:
                handleCommit_After(message, channel);
                break;
            default:
                logger.warn("未知类型{}", message.getBase_message().getType());
                break;
        }
    }

    public boolean checkMessage(PBFTMessage pbftMessage) {
//        if (!pbftMessage.getDigest().equals(pbftMessage.getBlock().getHash())) {
//            logger.error("哈希不匹配，开始视图切换");
//            return false;
//        }
//        Element pk = BLS.PemToPk(pbftMessage.getPublickey());
        String index=pbftMessage.getBase_message().getNodeIndex();
        X509Certificate certificate=Util.parseCertificate(path+index+"/node.cer");
        Element pk=Util.getCertPublicKey(certificate);
        Element sig = BLS.PemToSig(pbftMessage.getSignature());
        if (!BLS.verify(sig, pk, pbftMessage.getDigest())) {
            logger.error("签名不匹配，开始视图切换");
            return false;
        }
        if (pbftMessage.getBase_message().getView() != node.getView()) {
            logger.error("视图不匹配，开始视图切换");
            return false;
        }
        if (pbftMessage.getBase_message().getSequence() != node.getSequence()) {
            logger.error("序列号不匹配，开始视图切换");
            return false;
        }
        return true;
    }

    public boolean checkAggMessage(PBFTMessage pbftMessage) {
        List<String> nodes = new ArrayList<>();
        nodes = pbftMessage.getNodes();
        List<Element> pks = new ArrayList<>();
        for (String node : nodes) {
            X509Certificate certificate = Util.parseCertificate(path + node + "/node.cer");
            Element pk = Util.getCertPublicKey(certificate);
            pks.add(pk);
        }
        Element pk = BLS.AggregatePublicKey(pks);
        Element sig = BLS.PemToSig(pbftMessage.getSignature());
        if (!BLS.verify(sig, pk, pbftMessage.getDigest())) {
            logger.error("签名不匹配，开始视图切换");
            return false;
        }
        if (pbftMessage.getBase_message().getView() != node.getView()) {
            logger.error("视图不匹配，开始视图切换");
            return false;
        }
        if (pbftMessage.getBase_message().getSequence() != node.getSequence()) {
            logger.error("序列号不匹配，开始视图切换");
            return false;
        }
        return true;
    }

    public void handleViewChange(PBFTMessage message) {
        // 先保存其他节点的ViewChange消息再检测主节点活性
        String data = message.getBase_message().getData();
//        String public_key = message.getPublickey();
//        Element pk = BLS.PemToPk(public_key);
        X509Certificate certificate=Util.parseCertificate(path+message.getBase_message().getNodeIndex()+"/node.cer");
        Element pk=Util.getCertPublicKey(certificate);
        String hash = blockCache.getLatestBlock().getHash();
        VRF.verify(hash, pk, data, message.getBase_message().getNodeIndex());
        // 保存
        PBFTInfo.addViewChange(message);
        //主节点还没有选出来，那么就开始选举
        if (Common.index == null) {
            logger.warn("主节点信息不存在，直接触发选举");
            common.handleElection();
            return;
        }
        //有主节点的信息，主节点可能失效
        NodeInfo primaryNode = AllNodeInfo.nodes.get(Common.index);
        // 检查当前节点是否已发送过
        int currentView = node.getView();
        int newView = currentView + 1;
        // 发送活性检测消息给主节点
        PBFTMessage testMessage = new PBFTMessage();
        BaseMessage msg = new BaseMessage();
        msg.setType(BlockConstant.TEST);
        msg.setNodeIndex(node.getIndex());
        testMessage.setBase_message(msg);
//        testMessage.setType(BlockConstant.TEST);
//        testMessage.setNodeIndex(node.getIndex());
        SocketChannel channel = AllNodeInfo.channels.get(
                "/" + primaryNode.getIP() + ":" + primaryNode.getPort() + ":server");
        if (channel != null) {
            sendMessage(channel, JSON.toJSONString(testMessage));
            // 启动活性检测定时器（2秒）
            viewChangeTimeoutHandler = viewChangeScheduler.schedule(() -> {
                logger.warn("主节点响应超时，触发选举");
                // 再次检查是否已经发送过（防止定时器延迟导致重复）
                if (!PBFTInfo.viewChangeMessages.containsKey(newView)
                        || !PBFTInfo.viewChangeMessages.get(newView).containsKey(node.getIndex()))
                    common.handleElection();
                else
                    logger.info("响应超时但已发送过 ViewChange，不再重复");
            }, 1, TimeUnit.SECONDS);
        } else {
            logger.warn("无法连接到主节点，直接触发选举");
            common.handleElection();
        }
    }

    public void handleNewView(PBFTMessage message) {
        X509Certificate certificate=Util.parseCertificate(path+message.getBase_message().getNodeIndex()+"/node.cer");
        Element pk = Util.getCertPublicKey(certificate);
//        Element pk = BLS.PemToPk(message.getPublickey());
        Element sig = BLS.PemToSig(message.getSignature());
        if (!BLS.verify(sig, pk, message.getDigest())) {
            logger.error("签名不匹配，开始视图切换");
            return;
        }
        if (message.getAgg_viewChange().getNodes().size() > 2 * node.getF()) {
            NewView viewChanges = message.getAgg_viewChange();//获取客户端聚合消息
            // 获取待验证的消息列表（假设message.getMessages()返回List<Object>）
            List<String> digests = new ArrayList<>();
            for (BaseMessage msg : viewChanges.getViewChanges()) {
                // 将Object序列化为与签名时相同的字符串
                String digest = CryptoUtil.SHA256(msg.toString());
                digests.add(digest);
            }
            List<Element> publicKeys = new ArrayList<>();
//            for (String publickey : viewChanges.getPublicKeys())
//                publicKeys.add(BLS.PemToPk(publickey));
            for(String node:viewChanges.getNodes()) {
                X509Certificate cert=Util.parseCertificate(path+node+"/node.cer");
                Element node_pk=Util.getCertPublicKey(cert);
                publicKeys.add(node_pk);
            }
            Element agg_sig = BLS.PemToSig(viewChanges.getAgg_signature());
            if (BLS.AggregateVerifyDifferentMessages(agg_sig, publicKeys, digests)) {
                logger.info("验证通过");//聚合验证
                common.updateStatus(message.getBase_message().getView(), message.getBase_message().getNodeIndex());
            } else
                logger.info("验证失败");
        }
    }

    public void handlePre_PrePare(PBFTMessage pbftMessage, SocketChannel channel) {
        String key = getPhaseKey(pbftMessage.getBase_message().getView(), pbftMessage.getBase_message().getSequence() - 1);
        if ("Reply".equals(consensusPhase.get(key)) || consensusPhase.get(key) == null) {//判断上一轮共识
            consensusPhase.clear();//上一个阶段结束
            key = getPhaseKey(pbftMessage.getBase_message().getView(), pbftMessage.getBase_message().getSequence());
            consensusPhase.put(key, "PrePare");//下一个阶段开始
        } else
            logger.warn("{}消息正在共识", node.getSequence());
        logger.info("-----开始验证客户端聚合签名-----");
        //多一步，验证客户端原始请求
        Message message = pbftMessage.getAgg_message();//获取客户端聚合消息
        // 获取待验证的消息列表（假设message.getMessages()返回List<Object>）
        List<String> verifyMessages = new ArrayList<>();
        for (Object msgObj : message.getRequests()) {
            // 将Object序列化为与签名时相同的字符串
            String msgStr = msgObj.toString();
            verifyMessages.add(msgStr);
        }
        List<String> clients=pbftMessage.getAgg_message().getClients();
        List<Element> publicKeys = new ArrayList<>();
//        for (String publickey : message.getPublicKeys())
//            publicKeys.add(BLS.PemToPk(publickey));
        for (String client:clients){
            X509Certificate certificate=Util.parseCertificate(path+client+"/org_cert.cer");
            Element pk=Util.getCertPublicKey(certificate);
            publicKeys.add(pk);
        }
        Element agg_sig = BLS.PemToSig(message.getAgg_signature());
        BLS.AggregateVerifyDifferentMessages(agg_sig, publicKeys, verifyMessages);//聚合验证
        logger.info("-----结束验证客户端聚合签名-----");
        // 主节点发送重复消息
        if (PBFTInfo.getPrePreparesForViewAndSequence(node.getView(), node.getSequence()) != null) {
            // 视图切换
            logger.info("检测到重复消息");
            PBFTInfo.clearBlock();//清空上一轮主节点打包的区块
            common.handleElection();
            return;
        }

        if (!checkMessage(pbftMessage)) {
            // 视图切换
            logger.warn("消息验证失败，触发视图切换");
            PBFTInfo.clearBlock();//清空上一轮主节点打包的区块
            common.handleElection();
            return;
        }
        //保存pre_prepare消息
        PBFTInfo.addPrePrepare(pbftMessage);
        //取消自己转播的request消息计时器
        pbftMessage.getBase_message().getBlock().getBlockInfo().forEach(reqObj -> {
            Request req = JSON.parseObject(JSON.toJSONString(reqObj), Request.class);
            String digest = CryptoUtil.SHA256(JSON.toJSONString(req, SerializerFeature.SortField));
            appServer.cancelRequestTimer(digest);
        });
        // prepareMessage.setBlock(pbftMessage.getBlock());//不设置区块
        PBFTInfo.setBlock(pbftMessage.getBase_message().getView(), pbftMessage.getBase_message().getBlock());//区块保存，以待后用
        PBFTMessage prepareMessage = new PBFTMessage();
        prepareMessage.setDigest(pbftMessage.getDigest());//设置摘要
        BaseMessage msg = new BaseMessage();
        msg.setType(BlockConstant.PREPARE);
        msg.setView(node.getView());
        msg.setSequence(node.getSequence());
        msg.setNodeIndex(node.getIndex());
        prepareMessage.setBase_message(msg);
//        prepareMessage.setNodeIndex(node.getIndex());//设置节点索引
//        prepareMessage.setPublickey(node.getPublicKey());//设置公钥
//        prepareMessage.setView(node.getView());//视图号
//        prepareMessage.setSequence(node.getSequence());//序列号
        // 使用节点私钥进行签名
//        Element sk = BLS.PemToSk(node.getPrivateKey());
        Element sk=Util.readPrivateKey(path+node.getIndex()+"/node_sk.key");
        String sig = BLS.SigToPem(BLS.sign(pbftMessage.getDigest(), sk));
        prepareMessage.setSignature(sig);//签名

        if (sendMessage(channel, JSON.toJSONString(prepareMessage)))
            logger.info("成功发送prepare回复");

        //已保存prepare消息
        PBFTInfo.addPrepare(prepareMessage);
        // 启动Prepare阶段计时器（唯一键：view + sequence）
        // 计时器键,不适用generateKey避免阶段不同导致问题
        String prepareKey = "prepare" + pbftMessage.getBase_message().getView() + "-" + pbftMessage.getBase_message().getSequence();
        ScheduledFuture<?> prepareTimeout = prepareScheduler.schedule(() -> {
            logger.warn("Prepare阶段超时，触发视图切换 | Key: {}", prepareKey);
            common.handleElection();
            prepareTimeoutMap.remove(prepareKey);
        }, 5, TimeUnit.SECONDS);//5s内超时,避免主节点失效检测不到
        prepareTimeoutMap.put(prepareKey, prepareTimeout);
    }

    private void handlePrePare_After(PBFTMessage pbftMessage, SocketChannel channel) {
        String key = getPhaseKey(pbftMessage.getBase_message().getView(), pbftMessage.getBase_message().getSequence());
        if ("Prepare".equals(consensusPhase.get(key)))
            consensusPhase.put(key, "Commit");

        if (!checkAggMessage(pbftMessage)) {
            PBFTInfo.clearBlock();//清空上一轮主节点打包的区块
            common.handleElection();
            return;
        }
        // 取消Prepare阶段的计时器
        String prepareKey = "prepare" + pbftMessage.getBase_message().getView() + "-" + pbftMessage.getBase_message().getSequence();
        ScheduledFuture<?> prepareFuture = prepareTimeoutMap.get(prepareKey);
        if (prepareFuture != null) {
            prepareFuture.cancel(true);
            prepareTimeoutMap.remove(prepareKey);
        }
        PBFTInfo.addPrepare_After(pbftMessage);
        //构造Commit消息
        PBFTMessage commitMsg = new PBFTMessage();
        BaseMessage msg = new BaseMessage();
        msg.setType(BlockConstant.COMMIT);
        msg.setView(node.getView());
        msg.setSequence(node.getSequence());
        msg.setNodeIndex(node.getIndex());
        commitMsg.setBase_message(msg);
//        commitMsg.setType(BlockConstant.COMMIT);
        commitMsg.setDigest(pbftMessage.getDigest());
//        commitMsg.setNodeIndex(node.getIndex());
//        commitMsg.setPublickey(node.getPublicKey());
//        commitMsg.setView(node.getView());
//        commitMsg.setSequence(node.getSequence());

//        Element sk = BLS.PemToSk(node.getPrivateKey());
        Element sk=Util.readPrivateKey(path+node.getIndex()+"/node_sk.key");
        String sig = BLS.SigToPem(BLS.sign(pbftMessage.getDigest(), sk));
        commitMsg.setSignature(sig);

        if (sendMessage(channel, JSON.toJSONString(commitMsg)))
            logger.info("成功发送commit回复");
        PBFTInfo.addCommit(commitMsg);//保证所有节点日志都一致
        // 发送Commit消息后启动Commit计时器
        String commitKey = "commit" + pbftMessage.getBase_message().getView() + "-" + pbftMessage.getBase_message().getSequence();
        ScheduledFuture<?> commitTimeout = commitScheduler.schedule(() -> {
            logger.warn("Commit阶段超时，触发视图切换 | Key: {}", commitKey);
            PBFTInfo.clearBlock();//清空上一轮主节点打包的区块
            common.handleElection();
            commitTimeoutMap.remove(commitKey);
        }, 5, TimeUnit.SECONDS);
        commitTimeoutMap.put(commitKey, commitTimeout);
    }

    private void handleCommit_After(PBFTMessage message, SocketChannel channel) {
        String key = getPhaseKey(message.getBase_message().getView(), message.getBase_message().getSequence());
        if ("Commit".equals(consensusPhase.get(key)))
            consensusPhase.put(key, "Reply");//当前阶段为Reply
        if (!checkAggMessage(message)) {
            PBFTInfo.clearBlock();//清空上一轮主节点打包的区块
            common.handleElection();
            return;
        }
        if (node.getIsPrimary()) {
            logger.info("主节点忽略response消息");
            return;
        }
        //取消Commit阶段的计时器
        String commitKey = "commit" + message.getBase_message().getView() + "-" + message.getBase_message().getSequence();
        ScheduledFuture<?> commitFuture = commitTimeoutMap.get(commitKey);
        if (commitFuture != null) {
            commitFuture.cancel(true);
            commitTimeoutMap.remove(commitKey);
        }
        PBFTInfo.addCommit_After(message);
        Block block = PBFTInfo.getBlock(node.getView());
//        logger.info("区块信息{}",JSON.toJSONString(block));
        //Block block = message.getBlock()
//        blockService.addBlock(block);
//        for(Request request:block.getBlockInfo()){
//            JSONObject blockInfo=(JSONObject) JSON.toJSON(request);
//            BlockUtil.parseBlockInfo(block.getHash(), blockInfo);
//        }
        PBFTInfo.clearPrePrepareMessages();//清空pre_prepare消息
        PBFTInfo.clearAll();
        cleanupTimeouts(message.getBase_message().getView(), message.getBase_message().getSequence());
        //进入下轮共识
        node.setSequence(message.getBase_message().getSequence() + 1);
        PBFTMessage reply = new PBFTMessage();
        BaseMessage msg = new BaseMessage();
        msg.setBlock(block);
        reply.setBase_message(msg);
        appServer.responseClient(reply);
        appServer.signalConsensusCompletion();
        logger.info("保存区块,共识结束");
    }

    // 清理所有相关计时器
    private void cleanupTimeouts(int view, int sequence) {
        String prepareKey = "prepare" + view + "-" + sequence;
        String commitKey = "commit" + view + "-" + sequence;

        ScheduledFuture<?> prepareFuture = prepareTimeoutMap.get(prepareKey);
        if (prepareFuture != null) {
            prepareFuture.cancel(true);
            prepareTimeoutMap.remove(prepareKey);
        }

        ScheduledFuture<?> commitFuture = commitTimeoutMap.get(commitKey);
        if (commitFuture != null) {
            commitFuture.cancel(true);
            commitTimeoutMap.remove(commitKey);
        }
    }
}
