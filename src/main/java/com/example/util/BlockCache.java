package com.example.util;

import com.example.model.Block;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class BlockCache {
    /**
     * 当前节点的区块链结构
     */
    private List<Block> blockChain = new CopyOnWriteArrayList<Block>();
    /**
     * 已打包保存的业务数据集合
     */
    private List<Object> packedBlockInfo = new CopyOnWriteArrayList<Object>();


    /**
     * 获取最新的区块，即当前链上最后一个区块
     *
     * @return
     */
    public Block getLatestBlock() {
        return blockChain.size() > 0 ? blockChain.get(blockChain.size() - 1) : null;
    }

    public List<Block> getBlockChain() {
        return blockChain;
    }

    public void setBlockChain(List<Block> blockChain) {
        this.blockChain = blockChain;
    }

    public List<Object> getPackedBlockInfo() {
        return this.packedBlockInfo;
    }

    public void setPackedBlockInfo(List<Object> packedBlockInfo) {
        this.packedBlockInfo = packedBlockInfo;
    }
}
