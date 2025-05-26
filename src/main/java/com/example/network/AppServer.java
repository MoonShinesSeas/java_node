package com.example.network;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.model.*;
import com.example.model.PBFT.BaseMessage;
import com.example.model.PBFT.Message;
import com.example.model.PBFT.PBFTMessage;
import com.example.model.client.AppClient;
import com.example.model.client.ClientRequest;
import com.example.model.client.Request;
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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Component
public class AppServer {
    @Autowired
    private AppClient appClient;// 客户端

    @Autowired
    private Node node;

    @Autowired
    private BlockService blockService;

    @Autowired
    private BlockCache blockCache;

    @Autowired
    private Server server;

    @Lazy
    @Autowired
    private Common common;

    private final static String path = "src/main/resources/certs/";
    // 在AppServer类中添加
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();

    // consensus标志为锁和条件变量
    public final Object consensusLock = new Object();

    public boolean consensusOngoing = false;

    public void start() {
        Thread thread = new Thread(new connection());
        thread.start();
    }

    private static final Logger logger = LoggerFactory.getLogger(AppServer.class);

    private final ConcurrentMap<String, ScheduledFuture<?>> requestTimers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private final int size = 1024 * 4096;

    // 线程安全请求队列
    public BlockingQueue<ClientRequest> requestQueue = new LinkedBlockingQueue<>();

    // 待处理的客户端请求队列
    private BlockingQueue<ClientRequest> pendingRequests = new LinkedBlockingQueue<>();

    // 处理待处理请求的方法
    public void resendPendingRequests() {
        // 1. 首先处理未完成的共识消息（高优先级）
        if (node.getIsPrimary())
            processUnfinishedConsensusMessages();
        // 2. 处理pending队列中的客户端请求
        if (node.getIsPrimary()) {
            // 主节点直接处理请求：将pending队列中的请求加入处理队列
            List<ClientRequest> retryRequests = new ArrayList<>();
            pendingRequests.drainTo(retryRequests);
            for (ClientRequest request : retryRequests) {
                requestQueue.offer(request); // 加入主节点的处理队列
                logger.info("主节点处理待处理请求: {}", request.getRequest().getRequestId());
            }
        } else {
            // 非主节点转发请求给主节点
            NodeInfo primaryNode = AllNodeInfo.getNodes(Common.index);
            if (primaryNode == null) {
                logger.warn("主节点信息不可用，暂存请求等待下次选举");
                return;
            }
            List<ClientRequest> retryRequests = new ArrayList<>();
            pendingRequests.drainTo(retryRequests);
            for (ClientRequest request : retryRequests) {
                try {
                    forwardRequestToPrimary(request, primaryNode);
                } catch (Exception e) {
                    logger.error("重转发请求到主节点失败，重新加入队列", e);
                    pendingRequests.offer(request);
                    common.handleElection();
                }
            }
        }
    }

    // 处理未完成的共识消息
    private void processUnfinishedConsensusMessages() {
        // 获取上一个视图中未完成的Pre-Prepare消息
        int previousView = node.getView() - 1;
        Message message = PBFTInfo.getPrePreparesForViewAndSequence(previousView, node.getSequence());
        if (message == null)
            return;//没有待恢复的消息
        long timestamp = System.currentTimeMillis();
        String hash = blockService.calculateHash(blockCache.getLatestBlock().getHash(), message.getRequests(), timestamp);
        Block block = blockService.createNewBlock(blockCache.getLatestBlock().getHash(), hash,
                message.getRequests(), timestamp);// 打包区块

        PBFTMessage pbftMessage = new PBFTMessage();
        BaseMessage msg = new BaseMessage();
        msg.setType(BlockConstant.PRE_PREPARE);
        msg.setView(node.getView());
        msg.setSequence(node.getSequence());
        msg.setBlock(block);
        msg.setNodeIndex(node.getIndex());
        String digest = CryptoUtil.SHA256(msg.toString());
        pbftMessage.setBase_message(msg);
//        pbftMessage.setType(BlockConstant.PRE_PREPARE);
//        pbftMessage.setView(node.getView());
//        pbftMessage.setSequence(node.getSequence());
//        pbftMessage.setBlock(block);
//        Element privateKey = BLS.PemToSk(node.getPrivateKey());
//        Element publicKey = BLS.PemToPk(node.getPublicKey());
        Element privateKey = Util.readPrivateKey(path + node.getIndex() + "/node_sk.key");
//        pbftMessage.setPublickey(BLS.PkToPem(publicKey));
        pbftMessage.setSignature(BLS.SigToPem(BLS.sign(digest, privateKey)));// 签名
//        pbftMessage.setNodeIndex(node.getIndex());
        pbftMessage.setDigest(digest);

        pbftMessage.setAgg_message(message);//发送原始客户端请求
        PBFTInfo.setBlock(pbftMessage.getBase_message().getView(), block);//保存区块
        server.onPre_PrePare(pbftMessage);
        // 清理已处理的旧视图消息
        PBFTInfo.clearPrePrepareMessages();
    }

    // 新建线程接收客户端消息
    private class connection implements Runnable {
        @Override
        public void run() {
            try (Selector selector = Selector.open();
                 ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {

                serverSocketChannel.socket().bind(new InetSocketAddress(node.getApp_port()));
                serverSocketChannel.configureBlocking(false);
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
                String localIp = CommonUtil.getLocalIp();
                logger.info("app服务端启动，监听{}:{}", localIp, node.getApp_port());
                while (true) {
                    selector.select();
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        if (key.isAcceptable())
                            handleAccept(key, selector);
                        else if (key.isReadable())
                            handleRead(key);
                        keyIterator.remove();
                    }
                }
            } catch (IOException e) {
                logger.warn("app服务端，监听异常{}", e.getMessage());
            }
        }
    }

    private void handleAccept(SelectionKey key, Selector selector) {
        try {
            ServerSocketChannel serverchannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverchannel.accept();
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ);
            logger.info("{}已连接", clientChannel.getRemoteAddress());
        } catch (Exception e) {
            System.out.println("连接异常" + e.getMessage());
        }
    }

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
                System.out.println("节点: " + socketChannel.getRemoteAddress() + "断开连接");
                socketChannel.close();
            }
        } catch (Exception e) {
            System.out.println("监听消息异常: " + e.getMessage());
            ChannelUtil.close(socketChannel);
        }
    }

    // 发送消息到指定连接
    private static boolean sendMessage(SocketChannel channel, String msg) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
            logger.info("发送消息{}", msg);
            channel.write(buffer);
            return true;
        } catch (IOException e) {
            System.err.println("发送消息失败: " + e.getMessage());
            close(channel);
            return false;
        }
    }

    // 关闭指定连接
    private static void close(SocketChannel channel) {
        try {
            channel.close();
        } catch (Exception e) {
            System.out.println("关闭指定连接 " + e.getMessage());
        }
    }

    private void handleMessage(String msg, SocketChannel channel) {
        ClientRequest request = JSON.parseObject(msg, ClientRequest.class);
        logger.info("处理{}的消息", request.getRequest().getClientId());
        handleRequest(request, channel);
    }

    public class BlockProcessor implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                if (!common.isElectionOngoing()) {
                    try {
                        List<ClientRequest> batch = new ArrayList<>();
                        // 等待3秒或收集最多30个请求
                        long deadline = System.currentTimeMillis() + 3000;
                        while (batch.size() < 50 && System.currentTimeMillis() < deadline) {
//                        while(batch.size()<50){
                            long remaining = deadline - System.currentTimeMillis();
                            ClientRequest request = requestQueue.poll(remaining, TimeUnit.MILLISECONDS);
//                            ClientRequest request = requestQueue.poll();
                            if (request != null) {
                                batch.add(request);
                                requestQueue.drainTo(batch, 50 - batch.size());
                            }
                        }
                        if (!batch.isEmpty()) {
                            List<Request> blockInfo = new ArrayList<>();
//                            List<String> publicKeys = new ArrayList<>();//公钥聚合
                            List<Element> signatures = new ArrayList<>();//签名
                            List<String> clients = new ArrayList<>();

                            for (ClientRequest b : batch) {
                                blockInfo.add(b.getRequest());
//                                publicKeys.add(b.getPublicKey());//公钥加入
                                signatures.add(BLS.PemToSig(b.getSignature()));
                                clients.add(b.getClient());
                            }

                            long timestamp = System.currentTimeMillis();
                            String hash = blockService.calculateHash(blockCache.getLatestBlock().getHash(), blockInfo, timestamp);
                            Block block = blockService.createNewBlock(blockCache.getLatestBlock().getHash(), hash,
                                    blockInfo, timestamp);// 打包区块
                            Element agg_sig = BLS.AggregateSignatures(signatures);
                            //原始请求
                            Message message = new Message();
                            message.setClients(clients);
//                            message.setPublicKeys(publicKeys);
                            message.setAgg_signature(BLS.SigToPem(agg_sig));
                            message.setRequests(blockInfo);
                            //聚合多个签名请求
                            PBFTMessage pbftMessage = new PBFTMessage();
                            BaseMessage msg = new BaseMessage();
                            msg.setType(BlockConstant.PRE_PREPARE);
                            msg.setView(node.getView());
                            msg.setSequence(node.getSequence());
                            msg.setBlock(block);
                            msg.setNodeIndex(node.getIndex());
                            String digest = CryptoUtil.SHA256(msg.toString());
                            pbftMessage.setBase_message(msg);
//                            pbftMessage.setType(BlockConstant.PRE_PREPARE);
//                            pbftMessage.setView(node.getView());
//                            pbftMessage.setSequence(node.getSequence());
//                            pbftMessage.setBlock(block);
                            //主节点处理
//                            Element privateKey = BLS.PemToSk(node.getPrivateKey());
//                            Element publicKey = BLS.PemToPk(node.getPublicKey());
//                            pbftMessage.setPublickey(BLS.PkToPem(publicKey));
                            Element privateKey = Util.readPrivateKey(path + node.getIndex() + "/node_sk.key");
                            pbftMessage.setSignature(BLS.SigToPem(BLS.sign(digest, privateKey)));// 对这个区块签名
//                            pbftMessage.setNodeIndex(node.getIndex());
                            pbftMessage.setDigest(digest);
                            pbftMessage.setAgg_message(message);//发送原始客户端请求

                            PBFTInfo.setBlock(pbftMessage.getBase_message().getView(), block);//保存区块
                            server.onPre_PrePare(pbftMessage);
                            // 阻塞等待共识完成
                            synchronized (consensusLock) {
                                consensusOngoing = true;
                                while (consensusOngoing) {
                                    consensusLock.wait();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    public void handleRequest(ClientRequest request, SocketChannel channel) {
        // 副本节点收到客户端节点消息
        if (!node.getIsPrimary()) {
            //正在选举，后续消息，放入等待队列
            if (common.isElectionOngoing()) {
                if (pendingRequests.offer(request))
                    System.out.println("缓存到队列" + pendingRequests.size());
                close(channel);
                return;//退出处理
            } else {//不选举
                NodeInfo primaryNode = AllNodeInfo.getNodes(Common.index);
                // 异步执行主节点活性检测
                CompletableFuture.runAsync(() -> {
                    try (SocketChannel testChannel = SocketChannel.open()) {
                        testChannel.configureBlocking(true);
                        testChannel.socket().connect(
                                new InetSocketAddress(primaryNode.getIP(), primaryNode.getPort()),
                                2000
                        );

                        PBFTMessage testMsg = new PBFTMessage();
                        BaseMessage msg = new BaseMessage();
                        msg.setType(BlockConstant.TEST);
                        msg.setNodeIndex(node.getIndex());
                        testMsg.setBase_message(msg);
                        // 异步发送测试消息
                        if (!sendMessage(testChannel, JSON.toJSONString(testMsg)))
                            throw new IOException("发送测试消息失败");

                        // 设置读取超时
                        testChannel.socket().setSoTimeout(2000);
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        if (testChannel.read(buffer) == -1)
                            throw new IOException("主节点无响应");
                        else {
                            try {
                                Thread.sleep(100);
                            } catch (Exception e) {
                                System.out.println("休眠100ms异常" + e.getMessage());
                            }//避免立即发送导致粘包
                            forwardRequestToPrimary(request, primaryNode);
                        }
                    } catch (SocketTimeoutException e) {
                        logger.warn("主节点响应超时，触发选举");
                        pendingRequests.offer(request);
                        common.handleElection();
                    } catch (IOException e) {
                        logger.warn("主节点检测失败: {}", e.getMessage());
                        pendingRequests.offer(request);
                        common.handleElection();
                    }
                }, networkExecutor);
                close(channel);
                return;
            }
        }
        //主节点接收到消息
        /*
         * 验证客户端消息
         */
        // 提取公钥
        Element client_pk = BLS.PemToPk(request.getPublicKey());
        String client_signature = request.getSignature();
        //验证客户端摘要
        String coreData = request.getRequest().getClientId() + request.getRequest().getRequestId()
                + request.getRequest().getTimestamp()
                + request.getRequest().getOperation()
                + request.getRequest().getData();
        // 计算摘要
        String digest = CryptoUtil.SHA256(JSON.toJSONString(request.getRequest()));
        if (!digest.equals(request.getDigest())) {
            logger.warn("验证消息摘要不通过");
            close(channel);
            return;
        }
        Element client_sig = BLS.PemToSig(client_signature);
        if (!BLS.verify(client_sig, client_pk, coreData)) {
            System.out.println("验证消息签名失败");
            close(channel);
            return;
        }
        requestQueue.offer(request); // 加入队列
        close(channel);
    }

    private void forwardRequestToPrimary(ClientRequest request, NodeInfo primaryNode) {
        CompletableFuture.runAsync(() -> {
            try (SocketChannel primaryChannel = SocketChannel.open()) {
                primaryChannel.configureBlocking(true);
                // 设置连接超时（示例：3秒）
                primaryChannel.socket().connect(
                        new InetSocketAddress(primaryNode.getIP(), primaryNode.getPort() + 100),
                        3000
                );//连接到主节点App端口

                String digest = computeRequestDigest(request);
                ScheduledFuture<?> timer = scheduler.schedule(() -> {
                    logger.warn("Request转发超时，触发选举");
                    pendingRequests.offer(request); // 超时后重新入队
                    common.handleElection();
                }, 10, TimeUnit.SECONDS); // 超时时间缩短为10秒
                requestTimers.put(digest, timer);//设置超时
//                if (sendMessage(primaryChannel, JSON.toJSONString(request))) {
//                    timer.cancel(true); // 发送成功则取消定时器
//                    requestTimers.remove(digest);
//                }
                sendMessage(primaryChannel, JSON.toJSONString(request));
            } catch (IOException e) {
                logger.error("转发请求失败: {}", e.getMessage());
                pendingRequests.offer(request);
                common.handleElection();
            }
        }, Executors.newCachedThreadPool()); // 使用线程池处理异步任务
    }

    private String computeRequestDigest(ClientRequest request) {
        return CryptoUtil.SHA256(
                JSON.toJSONString(request.getRequest(), SerializerFeature.SortField));
    }

    //取消收到了pre_prepare消息的计时器
    public void cancelRequestTimer(String reqHash) {
        ScheduledFuture<?> timer = requestTimers.remove(reqHash);
        if (timer != null) {
            timer.cancel(true);
            logger.info("取消请求 {} 的定时器", reqHash);
        }
    }

    // 共识完成后调用此方法释放共识锁
    public void signalConsensusCompletion() {
        synchronized (consensusLock) {
            consensusOngoing = false;
            consensusLock.notifyAll();
        }
    }

    //返回客户端
    public void responseClient(PBFTMessage pbftMessage) {
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(appClient.getIp(), appClient.getPort()));
            sendMessage(channel, JSON.toJSONString(pbftMessage.getBase_message().getBlock()));
        } catch (Exception e) {
            logger.warn("返回客户端失败{}", e.getMessage());
        }
    }

}
