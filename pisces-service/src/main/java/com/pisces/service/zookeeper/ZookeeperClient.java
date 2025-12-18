package com.pisces.service.zookeeper;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Zookeeper客户端封装
 */
@Slf4j
@Component
public class ZookeeperClient {
    
    @Autowired
    private ZookeeperConfig zookeeperConfig;
    
    private CuratorFramework client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @PostConstruct
    public void init() {
        client = CuratorFrameworkFactory.builder()
                .connectString(zookeeperConfig.getConnectString())
                .sessionTimeoutMs(zookeeperConfig.getSessionTimeoutMs())
                .connectionTimeoutMs(zookeeperConfig.getConnectionTimeoutMs())
                .retryPolicy(new RetryNTimes(zookeeperConfig.getMaxRetries(), 1000))
                .build();
        
        client.start();
        log.info("Zookeeper客户端启动成功，连接地址: {}", zookeeperConfig.getConnectString());
    }
    
    @PreDestroy
    public void destroy() {
        if (client != null) {
            client.close();
            log.info("Zookeeper客户端已关闭");
        }
    }
    
    /**
     * 创建节点
     */
    public void createNode(String path, String data) throws Exception {
        createNode(path, data, CreateMode.PERSISTENT);
    }
    
    /**
     * 创建节点（指定模式）
     */
    public void createNode(String path, String data, CreateMode mode) throws Exception {
        String fullPath = getFullPath(path);
        if (client.checkExists().forPath(fullPath) == null) {
            client.create()
                    .creatingParentsIfNeeded()
                    .withMode(mode)
                    .forPath(fullPath, data.getBytes(StandardCharsets.UTF_8));
            log.debug("创建Zookeeper节点: {}", fullPath);
        } else {
            updateNode(path, data);
        }
    }
    
    /**
     * 更新节点数据
     */
    public void updateNode(String path, String data) throws Exception {
        String fullPath = getFullPath(path);
        client.setData().forPath(fullPath, data.getBytes(StandardCharsets.UTF_8));
        log.debug("更新Zookeeper节点: {}", fullPath);
    }
    
    /**
     * 获取节点数据
     */
    public String getNodeData(String path) throws Exception {
        String fullPath = getFullPath(path);
        Stat stat = client.checkExists().forPath(fullPath);
        if (stat == null) {
            return null;
        }
        byte[] data = client.getData().forPath(fullPath);
        return data != null ? new String(data, StandardCharsets.UTF_8) : null;
    }
    
    /**
     * 删除节点
     */
    public void deleteNode(String path) throws Exception {
        String fullPath = getFullPath(path);
        Stat stat = client.checkExists().forPath(fullPath);
        if (stat != null) {
            client.delete().deletingChildrenIfNeeded().forPath(fullPath);
            log.debug("删除Zookeeper节点: {}", fullPath);
        }
    }
    
    /**
     * 节点是否存在
     */
    public boolean exists(String path) throws Exception {
        String fullPath = getFullPath(path);
        return client.checkExists().forPath(fullPath) != null;
    }
    
    /**
     * 保存对象为JSON
     */
    public void saveObject(String path, Object obj) throws Exception {
        String json = objectMapper.writeValueAsString(obj);
        createNode(path, json);
    }
    
    /**
     * 获取对象
     */
    public <T> T getObject(String path, Class<T> clazz) throws Exception {
        String json = getNodeData(path);
        if (json == null) {
            return null;
        }
        return objectMapper.readValue(json, clazz);
    }
    
    /**
     * 获取完整路径
     */
    private String getFullPath(String path) {
        if (path.startsWith("/")) {
            return zookeeperConfig.getBasePath() + path;
        }
        return zookeeperConfig.getBasePath() + "/" + path;
    }
    
    /**
     * 获取子节点列表
     */
    public List<String> getChildren(String path) throws Exception {
        String fullPath = getFullPath(path);
        Stat stat = client.checkExists().forPath(fullPath);
        if (stat == null) {
            return new java.util.ArrayList<>();
        }
        return client.getChildren().forPath(fullPath);
    }
    
    /**
     * 获取CuratorFramework实例（用于高级操作）
     */
    public CuratorFramework getClient() {
        return client;
    }
}

