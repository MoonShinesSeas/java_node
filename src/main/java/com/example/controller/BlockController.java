package com.example.controller;

import com.alibaba.fastjson.JSON;
import com.example.model.transaction.Product;
import com.example.model.transaction.Transaction;
import com.example.service.BlockService;
import com.example.util.BlockCache;
import com.example.util.BlockUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import java.util.List;


@Controller
@CrossOrigin
public class BlockController {

	@Resource
	private BlockService blockService;
	
	@Autowired
	BlockCache blockCache;
	
	/**
	 * 查看当前节点区块链数据
	 * @return
	 */
	@GetMapping("/scan")
	@ResponseBody
	public String scanBlock() {
		return JSON.toJSONString(blockCache.getBlockChain());
	}
	
	/**
	 * 查看当前节点区块链数据
	 * @return
	 */
	@GetMapping("/data")
	@ResponseBody
	public String scanData() {
		return JSON.toJSONString(blockCache.getBlockChain());
	}

	@GetMapping("/product")
	@ResponseBody
	public List<Product> scanProduct() {return BlockUtil.readProducts();}

	@GetMapping("/transaction")
	@ResponseBody
	public List<Transaction> scanTransaction() {return BlockUtil.readTransactions();}

	@GetMapping("/product/{requestId}")
	@ResponseBody
	public Product scanProduct(@PathVariable("requestId") String requestId) {
		for(Product p:BlockUtil.readProducts())
			if(p.getRequestId().equals(requestId))
				return p;
		return null;
	}
}
