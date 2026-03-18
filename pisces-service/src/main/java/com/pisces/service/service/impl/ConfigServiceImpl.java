package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentLayer;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.repository.ExperimentConfigRepository;
import com.pisces.service.service.ConfigService;
import com.pisces.service.zookeeper.ZookeeperClient;
import com.pisces.service.zookeeper.ZookeeperConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
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
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final ZookeeperClient zookeeperClient;

    private final ZookeeperConfig zookeeperConfig;

    private final ExperimentConfigRepository experimentConfigRepository;
    
    private static final String EXPERIMENTS_PATH = "/experiments";
    
    /**
     * 配置变更监听器列表
     */
    private final ConcurrentHashMap<String, List<Consumer<ExperimentMetadata>>> listeners = new ConcurrentHashMap<>();

    private static final String LAYERS_PATH = "/layers";
    
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
        experimentConfigRepository.save(experimentId, metadata);
        
        // 如果Zookeeper可用，同步到Zookeeper
        if (zookeeperClient.isConnected()) {
            try {
                String path = EXPERIMENTS_PATH + "/" + experimentId;
                zookeeperClient.saveObject(path, metadata);
                log.info("保存实验配置到Zookeeper: {}", experimentId);
            } catch (Exception e) {
                log.warn("保存实验配置到Zookeeper失败（实验配置仓库已成功写入）: {}", experimentId, e);
            }
        } else {
            log.info("Zookeeper不可用，实验配置仅写入数据库仓库: {}", experimentId);
        }
    }
    
    /**
     * 获取实验配置
     */
    @Override
    public ExperimentMetadata getExperimentConfig(String experimentId) {
        ExperimentMetadata repositoryMetadata = experimentConfigRepository.findById(experimentId).orElse(null);
        if (repositoryMetadata != null) {
            return repositoryMetadata;
        }

        if (!zookeeperClient.isConnected()) {
            log.debug("Zookeeper不可用，数据库仓库中也未命中实验配置: {}", experimentId);
            return null;
        }
        
        try {
            String path = EXPERIMENTS_PATH + "/" + experimentId;
            ExperimentMetadata metadata = zookeeperClient.getObject(path, ExperimentMetadata.class);
            if (metadata != null) {
                experimentConfigRepository.save(experimentId, metadata);
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
        experimentConfigRepository.delete(experimentId);
        
        if (zookeeperClient.isConnected()) {
            try {
                String path = EXPERIMENTS_PATH + "/" + experimentId;
                zookeeperClient.deleteNode(path);
                log.info("从Zookeeper删除实验配置: {}", experimentId);
            } catch (Exception e) {
                log.warn("从Zookeeper删除实验配置失败: {}", experimentId, e);
            }
        } else {
            log.info("数据库仓库中的实验配置已删除，Zookeeper当前不可用: {}", experimentId);
        }
    }
    
    /**
     * 获取所有实验ID列表
     */
    @Override
    public List<String> getAllExperimentIds() throws Exception {
        List<String> repositoryExperimentIds = experimentConfigRepository.findAllExperimentIds();

        // 如果Zookeeper不可用，返回实验配置仓库中的实验ID
        if (!zookeeperClient.isConnected()) {
            log.debug("Zookeeper不可用，从实验配置仓库获取实验列表");
            return repositoryExperimentIds;
        }
        
        try {
            List<String> children = zookeeperClient.getChildren(EXPERIMENTS_PATH);
            log.debug("从Zookeeper获取实验列表: 数量={}", children != null ? children.size() : 0);
            if (children != null && !children.isEmpty()) {
                return children;
            }
            return repositoryExperimentIds;
        } catch (Exception e) {
            log.error("获取实验列表失败，返回实验配置仓库中的结果", e);
            return repositoryExperimentIds;
        }
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

    // ── 分层配置 CRUD ─────────────────────────────────────────────────────────

    @Override
    public void saveLayerConfig(String layerId, ExperimentLayer layer) throws Exception {
        if (!zookeeperClient.isConnected()) {
            throw new IllegalStateException("Zookeeper不可用，无法保存分层配置: " + layerId);
        }
        String path = LAYERS_PATH + "/" + layerId;
        zookeeperClient.saveObject(path, layer);
        log.info("保存分层配置到Zookeeper: {}", layerId);
    }

    @Override
    public ExperimentLayer getLayerConfig(String layerId) {
        if (!zookeeperClient.isConnected()) {
            return null;
        }
        try {
            String path = LAYERS_PATH + "/" + layerId;
            return zookeeperClient.getObject(path, ExperimentLayer.class);
        } catch (Exception e) {
            log.error("获取分层配置失败: {}", layerId, e);
            return null;
        }
    }

    @Override
    public void deleteLayerConfig(String layerId) throws Exception {
        if (!zookeeperClient.isConnected()) {
            throw new IllegalStateException("Zookeeper不可用，无法删除分层配置: " + layerId);
        }
        String path = LAYERS_PATH + "/" + layerId;
        zookeeperClient.deleteNode(path);
        log.info("从Zookeeper删除分层配置: {}", layerId);
    }
}
