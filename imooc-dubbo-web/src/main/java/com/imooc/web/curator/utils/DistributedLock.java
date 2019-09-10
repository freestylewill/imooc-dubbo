package com.imooc.web.curator.utils;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;


//分布式锁的实现工具类
public class DistributedLock {
    private CuratorFramework client=null;
    final  static Logger log= LoggerFactory.getLogger(DistributedLock.class);
    //用于挂起当前请求，并且等待上一个分布式锁释放
    private static CountDownLatch zkLockLatch=new CountDownLatch(1);
    //分布式锁的总结点名
    private static  final String ZK_LOCK_PROJECT="imooc-locks";
    //分布式节点
    private static  final  String DISTRIBUTED_LOCK ="distributed_lock";
    //构造函数
    public DistributedLock(CuratorFramework client){
        this.client=client;
    }

    //初始化锁
    public void init(){
        //使用命名空间
        client=client.usingNamespace("ZKLocks-Namespace");
        /**
         * 创建zk锁的总节点，相当于eclipse的工作空间下的项目
         * ZKLocks-Namespace
         *     \
         *      -imooc-locks
         *         \
         *          -distributed_lock
         *
         */
        try {
            if(client.checkExists().forPath("/"+ZK_LOCK_PROJECT)==null){
                client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                        .forPath("/"+ZK_LOCK_PROJECT);
            }
            //针对zk的分布式锁节点，创建相应的watch事件监听
            addWatcherToLock("/"+ZK_LOCK_PROJECT);
        }catch (Exception e){
            log.error("客户端连接zookeeper服务器错误。。。。请重试。。。");
        }
    }
    //获取锁，不占用---》创建节点，占用---》await释放
    public void  getLock(){
        //使用死循环，当且仅当上一个锁释放并且当前请求获得锁后才会跳出
           while (true){
               try {
               client.create()
                       .creatingParentsIfNeeded()
                       //CreateMode.EPHEMERAL====创建零时节点
                       .withMode(CreateMode.EPHEMERAL)
                       .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                       .forPath("/"+ZK_LOCK_PROJECT+"/"+DISTRIBUTED_LOCK);
               log.info("获得分布式锁成功....");
               return;                           //如果锁的节点能被创建成功，则锁没有被占用
           }catch (Exception e){
               log.info("获得分布式锁失败");
               try {
                   //如果没有获取到锁，需要重新设置同步资源值
                   if(zkLockLatch.getCount()<=0){
                       zkLockLatch=new CountDownLatch(1);
                   }
                   //阻塞线程----zkLockLatch.getCount()==0的时候，再执行此线程
                   zkLockLatch.await();
               } catch (InterruptedException ie){
                   ie.printStackTrace();
               }
           }
           }

    }
    //释放锁==========删除节点
    public boolean releaseLock(){
        try {
            if(client.checkExists().forPath("/"+ZK_LOCK_PROJECT+"/"+DISTRIBUTED_LOCK) !=null){
                client.delete().forPath("/"+ZK_LOCK_PROJECT+"/"+DISTRIBUTED_LOCK);
            }
        }catch (Exception e){
            e.printStackTrace();
            return  false;
        }
        log.info("分布式锁释放完毕");
        return true;
    }
    //创建watch监听
    //监听父节点的子节点remove，并且移除的是你设置的节点，进行countdown
    private void addWatcherToLock(String path) throws Exception {
        final PathChildrenCache cache=new PathChildrenCache(client,path,true);
        cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
        cache.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                //监听父节点的子节点remove
                if(event.getType().equals(PathChildrenCacheEvent.Type.CHILD_REMOVED)){
                    String path=event.getData().getPath();
                    log.info("上一个会话已释放锁或该会话已断开，节点路径为： "+path);
                    if(path.contains(DISTRIBUTED_LOCK)){
                        log.info("释放计数器，让当前请求来获得分布式锁。。。");
                        zkLockLatch.countDown();
                    }
                }
            }
        });
    }
}
