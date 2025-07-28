这是区块链平台的网络部分，区块链节点之间通过p2p网络通信
### 节点发现
通信模块基于NioSocket，这是比netty功能要弱的通信依赖，不支持心跳和粘包处理，后续有时间或许会更新netty版本。

节点之间是不能直接相互探测到的，局域网内广播也只能是试验，需要有种子节点或者锚节点，才能节点发现，于是我建立了`java_anchor`项目作为区块链的锚节点，
在NodeService类中通过代码
```java
client.ConnectToAnchor("127.0.0.1", 7150);
```
向锚节点发起链接
```java
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
```
每个节点相对于锚节点都是一个客户端，向锚节点发起链接，锚节点向客户端返回已知节点信息，建立链接后不出现错误是不会断开链接的。
![img](https://youke1.picui.cn/s1/2025/07/28/688793f41bb80.png)
锚节点发送节点信息如上图所示
区块链节点收到所有已知信息后进行链接
![img](https://youke1.picui.cn/s1/2025/07/28/6887945cede33.png)
### PBFT共识
区块链同步区块需要共识算法，我使用的是PBFT，但是我还没有实现PBFT中的检查点协议，只实现了视图切换和共识。

**PBFT第一步是选举出主节点，我采用了VRF可验证随机函数来进行主节点选举**
![img](https://youke1.picui.cn/s1/2025/07/28/688796cb85e68.png)
![img](https://youke1.picui.cn/s1/2025/07/28/6887970e51b8d.png)
启动系统达到指定节点数之后，节点之间会自动触发主节点选举
每个节点生成自己的vrf证明和挑战
{"base_message":{"data":"{\"challenge\":\"ZFRMBK8asgUOWU1OonIMLrE7oTk=\",\"response\":\"QdmH9ZJrmF/ShsjCktmAkIx+YSM=\",\"y\":\"kX6Jt3/8OHVy68BRyyTYJwTQIL8GTrkAEoLqTT+uxUyvSCYywIAG77tdSuA14YqMmwS5hX6F1FD6g+O6XJJiZnqzY9KCyNxvQlbKU1AmTk4jAq/fiqgZ8KmU3SUShQ4SwjBRJ4zgXsinEkcH+NMoAsDDozt5XziiK0tBVqdvfcM=\"}","nodeIndex":"node2","sequence":0,"type":0,"view":1},"digest":"10bc81a634fb36eaa3d13e750f2c124cc800c645a84883bee00801b5eff1946f","signature":"-----BEGIN SIGNATURE-----\ncpXySFCtkKizSnQSVtJQmBtSYmpUG8r4EzOUwraYQQpSTxvY1SZ+fT11UZtNd30b\nxh1r+F+tFehjoE/RGpSRDjyU49tb65CzxvJjaEhMEimoojpixSnggk3fqjqdqG9j\nSy/DSMt02bX6DhErl4A4Vkt+u5hM+WWqdEz9QTNOLqk=\n-----END SIGNATURE-----\n"}
向所有连接的节点进行广播，各个节点之间接收到后会进行验证，验证后进行比较，值最小的节点索引作为主节点
主节点选举出来后需要发布一个new-view消息
{"agg_viewChange":{"agg_signature":"-----BEGIN SIGNATURE-----\nkKEXhuXuwOUCyD3rNVDsDFmEoK6rGcWbcVxtcdIVlwVQQ6B5alu4iOHBHhcJP0Ll\neXIvg9qCyidzoVSOh5JGdklu9+CcvrLznwcaJbWZdiFuWrklEmmPTuEUO+eG8Hfi\nZu7O2Bi9UvJR2BHA/D+Q1r75s1gYjDX1zJ5Cf4+E+MM=\n-----END SIGNATURE-----\n","nodes":["node4","node2","node3","node1"],"viewChanges":[{"data":"{\"challenge\":\"a8ZR+weBbNFNhCNoDBnsMGzmLRc=\",\"response\":\"aOpxKlD2f1KSZldMduNlm+7Yc4w=\",\"y\":\"fPQHMF+7bSsSr2zy6vC1eIlpGeM73dDMaaPyo3R6Z9FYWkOorWvOluEkDP6yRqGrAbdeZ/AZyHmgVIbH+fmA7Tld+uORsM+nCcXQ1+izUu9DV4t8LQFkSMTINTJJDQ+87rr6aeUeb43xBXBiN3Iooi7z2VtDIvPcvtiarU1FW4Y=\"}","nodeIndex":"node4","sequence":0,"type":0,"view":1},{"data":"{\"challenge\":\"ZFRMBK8asgUOWU1OonIMLrE7oTk=\",\"response\":\"QdmH9ZJrmF/ShsjCktmAkIx+YSM=\",\"y\":\"kX6Jt3/8OHVy68BRyyTYJwTQIL8GTrkAEoLqTT+uxUyvSCYywIAG77tdSuA14YqMmwS5hX6F1FD6g+O6XJJiZnqzY9KCyNxvQlbKU1AmTk4jAq/fiqgZ8KmU3SUShQ4SwjBRJ4zgXsinEkcH+NMoAsDDozt5XziiK0tBVqdvfcM=\"}","nodeIndex":"node2","sequence":0,"type":0,"view":1},{"data":"{\"challenge\":\"U2LGp1WXR7wIgfLWkKEFAzsUokg=\",\"response\":\"GSLdiPHKNl6YsLahAZvO8MWKGH4=\",\"y\":\"ZL+yx/edYoRmCZroah1fN07WnXy0IA7Tke2ykiDjRS/ebZsqy97zrIr4AMrKdGuMApKSMC27qecgRBv2gfA3joFqdRouL3Nvq5GJztSAyRGuwkc7H60EYVpq2dnWBtzntYwEpfwsLUqYkDe7lbSxJ9Cr1KwO67SXdL/dbGhzdak=\"}","nodeIndex":"node3","sequence":0,"type":0,"view":1},{"data":"{\"challenge\":\"GvUWnKhuqSrokjZdh5SXOYQso9c=\",\"response\":\"cshqvrQaKzf2aAg04poDfSMPoZ8=\",\"y\":\"fnqSpDMAzsg3VNGkyIhB4P5AxoM/CTO2qVGtsQTC0+/YLPpC7mc9a0kHmKoHG2LY8brxFoer6p1ESfVL+BeK3VONij3JLb82TCnC6LuXbWQhcF7JOaBNaWcHSVxc9Tx9x1GbHgdg5OzBaHjkwKtY270Fes5YifSqWz1VFMe9/Hw=\"}","nodeIndex":"node1","sequence":0,"type":0,"view":1}]},"base_message":{"nodeIndex":"node2","sequence":0,"type":-3,"view":1},"digest":"33f44e82ba8a9f5be27e9fefbc9dfa4a9b57d3a8a377ce9182af4ed713e8df1a","signature":"-----BEGIN SIGNATURE-----\nazCXiv1ggPsjdGhxCPJX+URH6f21EOv8uJ/ca1dwOqcphS15HNIPLWXpgw9jef50\n3NolIN1CbZK52xEXX9KOlG2YKnLokN2MI7quGCZOVB4xZd+IDLV5CaohaONQ+azC\nPp876+wsgu+g0NdZ+DWk9+BSdc0y3pQ1XqnWxSCzjg8=\n-----END SIGNATURE-----\n"}
包含聚合签名和同意这个new-view消息的节点索引，聚合签名聚合的是各个节点发布自己的vrf值的签名，提供给各个节点进行验证。
**PBFT的共识过程复杂度很高，我采用了BLS聚合签名优化到O(n)的复杂度**
![img](https://youke1.picui.cn/s1/2025/07/28/688798571b669.png)
各个节点之间的证书以及预装(就是resources里面的证书)各个节点都是用根证书和节点证书(节点证书内包含公钥)来验证数字签名的有效性
当验证不通过的消息达到2f+1时系统就需要重启，因为作恶节点已经超过了承受能力，f在Node类中指定，
目前不支持动态节点，所以容错能力一开始就是算好的，四个节点容错一个节点，七个节点容错两个节点。
一个主节点在没有失效(宕机)时是不会进行视图切换的，宕机的节点也不会再参与共识了。
**创世区块**
我预计算创世区块的hash
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
//        System.out.println(blockService.calculateHash(null,genesisBlock.getBlockInfo(),time));

**共识测试**，在`java_client`项目中有
```java
    @Override
    public void run(ApplicationArguments args) throws Exception {
        clientService.sendConcurrentRequests(30, 2);
        start();
    }
```
这个意思是在项目启动时通过两个线程向区块链网络发送30(我的AppServer类设置的也是30个请求打一个区块)个模拟请求。
java_client测试图
![img](https://youke1.picui.cn/s1/2025/07/28/68879a7c102dc.png)
AppServer支持节点转发(客户端请求发送到非主节点，非主节点会向主节点进行转发)
![img](https://youke1.picui.cn/s1/2025/07/28/68879ae3a37e5.png)
共识完成

以上只是一个普通测试的过程，我没有启动数据库的部分，没有用netty导致超过80条请求可能就会粘包，客户端消息之间不能分割，十个yaml文件是通过idea使用时，
可以启动多个节点，BLS签名和数字证书分别是通过jpbc和bouncycastle来实现的。

启动流程是`java_anchor`、`java_node`中的各个节点、`java_client`进行测试
