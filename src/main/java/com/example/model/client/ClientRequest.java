package com.example.model.client;

import com.example.util.BLS;
import it.unisa.dia.gas.jpbc.Element;
import lombok.Data;

import java.io.Serializable;

@Data
public class ClientRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    // 安全验证
    private String signature; // 客户端签名

    private String client;

    private String publicKey;// 客户端公钥

    private String digest;

    private Request request;

    public ClientRequest() {
    }

    // 生成签名（客户端调用）
    public void sign(Element privateKey) {
        // 对核心数据签名（不包含签名字段本身）
        // clientId + requestId + timestamp + operation;
        String coreData = this.request.getClientId() + request.getRequestId() + request.getTimestamp()
                + request.getOperation() + this.getRequest().getData();
        String sig = BLS.SigToPem(BLS.sign(coreData,privateKey));
        this.signature = sig;
    }

}
