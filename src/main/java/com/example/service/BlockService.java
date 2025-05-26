package com.example.service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.example.model.client.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.example.model.Block;
import com.example.util.BlockCache;
import com.example.util.CryptoUtil;

/**
 * 区块链核心服务
 */
@Service
public class BlockService {
    private static final String path="src/main/resources/genesis.block";

    @Autowired
    BlockCache blockCache;

    /**
     * 创建新区块
     *
     * @param previousHash
     * @param hash
     * @return
     */
    public Block createNewBlock(String previousHash, String hash, List<Request> blockInfo,long timestamp) {
        Block block = new Block();
        block.setIndex(blockCache.getBlockChain().size() + 1);
        // 时间戳
        block.setTimestamp(timestamp);
        block.setBlockInfo(blockInfo);
        // 上一区块的哈希
        block.setPreviousHash(previousHash);
        // 当前区块的哈希
        block.setHash(hash);
        return block;
    }

    /**
     * 添加新区块到当前节点的区块链中
     *
     * @param newBlock
     */
    public boolean addBlock(Block newBlock) {
        // 先对新区块的合法性进行校验
        if (isValidNewBlock(newBlock, blockCache.getLatestBlock())) {
            blockCache.getBlockChain().add(newBlock);
            // 新区块的业务数据需要加入到已打包的交易集合里去
            blockCache.getPackedBlockInfo().addAll(newBlock.getBlockInfo());

            saveBlockchainToFile(path);
            return true;
        }
        return false;
    }

    /**
     * 验证新区块是否有效
     *
     * @param newBlock
     * @param previousBlock
     * @return
     */
    public boolean isValidNewBlock(Block newBlock, Block previousBlock) {
        if (!previousBlock.getHash().equals(newBlock.getPreviousHash())) {
            System.out.println("新区块的前一个区块hash验证不通过");
            return false;
        } else {
            // 验证新区块hash值的正确性
            String hash = calculateHash(newBlock.getPreviousHash(), newBlock.getBlockInfo(),newBlock.getTimestamp());
            if (!hash.equals(newBlock.getHash())) {
                System.out.println("新区块的hash无效: " + hash + " " + newBlock.getHash());
                return false;
            }
        }
        return true;
    }

    /**
     * 计算区块的hash
     *
     * @param previousHash
     * @return
     */
    public String calculateHash(String previousHash, List<Request> currentBlockInfo, long timestamp) {
        return CryptoUtil.SHA256(previousHash + JSON.toJSONString(currentBlockInfo)+timestamp);
    }

    /**
     * 保存区块链到文件
     *
     * @param filePath 文件路径
     */
    public void saveBlockchainToFile(String filePath) {
        List<Block> blockChain = blockCache.getBlockChain();
        try (FileWriter writer = new FileWriter(filePath)) {
            // 按区块索引排序，确保顺序正确
            blockChain.sort(Comparator.comparingInt(Block::getIndex));
            String jsonBlockChain = JSON.toJSONString(blockChain);
            writer.write(jsonBlockChain);
        } catch (IOException e) {
            System.out.println("保存区块到文件失败"+e.getMessage());
        }
    }

    public List<Block> loadBlockchainFromFile(String filePath) {
        List<Block> loadedChain = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String json = reader.lines().collect(Collectors.joining());
            loadedChain = JSON.parseArray(json, Block.class);
            loadedChain.sort(Comparator.comparingInt(Block::getIndex)); // 按索引排序

            // 验证每个区块的previousHash
            for (int i = 1; i < loadedChain.size(); i++) {
                Block current = loadedChain.get(i);
                Block previous = loadedChain.get(i - 1);
                if (!current.getPreviousHash().equals(previous.getHash())) {
                    System.err.println("区块 " + current.getIndex() + " 的previousHash不匹配");
                    return null;
                }
            }
        } catch (IOException e) {
            System.err.println("读取文件失败: " + e.getMessage());
        }
        return loadedChain;
    }
}
