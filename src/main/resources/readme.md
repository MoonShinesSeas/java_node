```java
public void func(){
    List<Request> requests = block.getBlockInfo().stream()
            .map(obj -> JSON.parseObject(JSON.toJSONString(obj), Request.class))
            .collect(Collectors.toList());
    for (Request request : requests) {
        if (request.getOperation()==0)
            try{
                ProductUtil.put(request.getRequestId(),request,1000*60);//三小时
            }catch (Exception e){
                logger.error("保存数据出错{}",e.getMessage());
            }
        else if (request.getOperation()==1) {
            try {
                OrderUtil.put(request.getRequestId(),request,1000*60);//三小时
            }catch (Exception e){
                logger.error("保存数据出错{}",e.getMessage());
            }
        }
    }
    PBFTInfo.printStatus();
}
```