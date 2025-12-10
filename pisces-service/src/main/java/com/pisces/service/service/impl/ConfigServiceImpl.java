package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.service.ConfigService;
import com.pisces.service.zookeeper.ZookeeperClient;
import com.pisces.service.zookeeper.ZookeeperConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 配置管理服务实现（基于Zookeeper）
 */
@Slf4j
@Service
public class ConfigServiceImpl implements ConfigService {
    
    @Autowired
    private ZookeeperClient zookeeperClient;
    
    @Autowired
    private ZookeeperConfig zookeeperConfig;
    
    private static final String EXPERIMENTS_PATH = "/experiments";
    
    /**
     * 配置变更监听器列表
     */
    private final ConcurrentHashMap<String, List<Consumer<ExperimentMetadata>>> listeners = new ConcurrentHashMap<>();
    
    /**
     * 配置缓存
     */
    private final ConcurrentHashMap<String, ExperimentMetadata> configCache = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        try {
            // 监听实验配置变化
            String basePath = zookeeperConfig.getBasePath() + EXPERIMENTS_PATH;
            PathChildrenCache cache = new PathChildrenCache(
                    zookeeperClient.getClient(),
                    basePath,
                    true
            );
            
            cache.getListenable().addListener((client, event) -> {
                ChildData data = event.getData();
                if (data != null) {
                    String path = data.getPath();
                    String experimentId = extractExperimentId(path);
                    if (experimentId != null) {
                        log.info("实验配置变更: {}", experimentId);
                        // 清除缓存，下次获取时重新加载
                        configCache.remove(experimentId);
                        notifyListeners(experimentId);
                    }
                }
            });
            
            cache.start();
            log.info("Zookeeper配置监听器启动成功");
        } catch (Exception e) {
            log.error("启动Zookeeper配置监听器失败", e);
        }
    }
    
    /**
     * 保存实验配置
     */
    @Override
    public void saveExperimentConfig(String experimentId, ExperimentMetadata metadata) throws Exception {
        String path = EXPERIMENTS_PATH + "/" + experimentId;
        zookeeperClient.saveObject(path, metadata);
        configCache.put(experimentId, metadata);
        log.info("保存实验配置: {}", experimentId);
    }
    
    /**
     * 获取实验配置
     */
    @Override
    public ExperimentMetadata getExperimentConfig(String experimentId) {
        // 先从缓存获取
        ExperimentMetadata cached = configCache.get(experimentId);
        if (cached != null) {
            return cached;
        }
        
        // 从Zookeeper获取
        try {
            String path = EXPERIMENTS_PATH + "/" + experimentId;
            ExperimentMetadata metadata = zookeeperClient.getObject(path, ExperimentMetadata.class);
            if (metadata != null) {
                configCache.put(experimentId, metadata);
            }
            return metadata;
        } catch (Exception e) {
            log.error("获取实验配置失败: {}", experimentId, e);
            return null;
        }
    }
    
    /**
     * 删除实验配置
     */
    @Override
    public void deleteExperimentConfig(String experimentId) throws Exception {
        String path = EXPERIMENTS_PATH + "/" + experimentId;
        zookeeperClient.deleteNode(path);
        configCache.remove(experimentId);
        log.info("删除实验配置: {}", experimentId);
    }
    
    /**
     * 注册配置变更监听器
     */
    @Override
    public void addConfigChangeListener(String experimentId, Consumer<ExperimentMetadata> listener) {
        listeners.computeIfAbsent(experimentId, k -> new ArrayList<>()).add(listener);
    }
    
    /**
     * 通知监听器
     */
    private void notifyListeners(String experimentId) {
        List<Consumer<ExperimentMetadata>> experimentListeners = listeners.get(experimentId);
        if (experimentListeners != null) {
            ExperimentMetadata metadata = getExperimentConfig(experimentId);
            if (metadata != null) {
                experimentListeners.forEach(listener -> {
                    try {
                        listener.accept(metadata);
                    } catch (Exception e) {
                        log.error("通知配置变更监听器失败", e);
                    }
                });
            }
        }
    }
    
    /**
     * 从路径中提取实验ID
     */
    private String extractExperimentId(String path) {
        String[] parts = path.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }
        return null;
    }
}

