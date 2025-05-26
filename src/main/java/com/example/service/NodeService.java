package com.example.service;

import com.example.model.Block;
import com.example.model.client.Request;
import com.example.network.AppServer;
import com.example.network.Client;
import com.example.network.Common;
import com.example.network.Server;
import com.example.util.BlockCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class NodeService implements ApplicationRunner {

    @Autowired
    private AppServer appServer;

    @Autowired
    private Client client;

    @Autowired
    private BlockCache blockCache;

    @Autowired
    private Server server;

    @Autowired
    private Common common;

    @Autowired
    private BlockService blockService;

    private static final String path="src/main/resources/genesis.block";

    @Override
    public void run(ApplicationArguments args) throws Exception {
//        Block genesisBlock = new Block();
//        // 设置创世区块高度为1
//        genesisBlock.setIndex(1);
//        long time = 1743519414348L;
//        genesisBlock.setTimestamp(time);
//        // 封装业务数据
//        List<Request> tsaList = new ArrayList<>();
//        String tsa = "创世区块";
//        Request request1 = new Request();
//        request1.setData(tsa);
//        request1.setTimestamp(time);
//        tsaList.add(request1);
//        genesisBlock.setBlockInfo(new ArrayList<>(tsaList));
////        System.out.println(blockService.calculateHash(null,genesisBlock.getBlockInfo(),time));
////         设置创世区块的hash值
//        genesisBlock.setHash("649a44a47b11c01d85278b6d2b92c72b1f07e02478c550be001db162109d63cb");
////         添加到已打包保存的业务数据集合中
//        blockCache.getPackedBlockInfo().addAll(new ArrayList<>(tsaList));
////         添加到区块链中
//        blockCache.getBlockChain().add(genesisBlock);
//         blockService.saveBlockchainToFile(path);
        List<Block> blocks = blockService.loadBlockchainFromFile(path);
        blockCache.setBlockChain(blocks);

        appServer.start();
        server.start();
        client.ConnectToAnchor("127.0.0.1", 7150);
//        client.ConnectToAnchor("anchor", 7050); // 使用服务名称而非IP
        common.listener();
        new Thread(appServer.new BlockProcessor()).start();
    }

}
