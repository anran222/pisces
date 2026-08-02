package com.pisces.service.service;

import com.pisces.common.model.ExperimentLayer;
import com.pisces.common.model.ExperimentConfigDraft;
import com.pisces.common.model.ExperimentConfigDraftApproval;
import com.pisces.common.model.ExperimentConfigVersion;
import com.pisces.common.model.ExperimentMetadata;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 配置管理服务接口（基于Zookeeper）
 */
public interface ConfigService {

    /**
     * 保存实验配置
     */
    void saveExperimentConfig(String experimentId, ExperimentMetadata metadata) throws Exception;

    /**
     * 获取实验配置
     */
    ExperimentMetadata getExperimentConfig(String experimentId);

    /**
     * 删除实验配置
     */
    void deleteExperimentConfig(String experimentId) throws Exception;

    /**
     * 获取所有实验ID列表
     */
    List<String> getAllExperimentIds() throws Exception;

    /**
     * 注册配置变更监听器
     */
    void addConfigChangeListener(String experimentId, Consumer<ExperimentMetadata> listener);

    /**
     * 查询实验配置变更序列。
     *
     * @param experimentId 实验ID
     * @return 变更序列
     */
    long getExperimentConfigChangeSequence(String experimentId);

    /**
     * 等待实验配置发生变更。
     *
     * @param experimentId 实验ID
     * @param knownChangeSequence 已知变更序列
     * @param waitMillis 最大等待毫秒数
     * @throws InterruptedException 当前线程被中断时抛出
     */
    void waitForExperimentConfigChange(String experimentId, long knownChangeSequence, long waitMillis)
            throws InterruptedException;

    /**
     * 保存实验配置版本快照
     *
     * @param experimentId 实验ID
     * @param metadata 配置快照
     * @param publishedBy 发布人
     * @param publishComment 发布备注
     * @param sourceConfigVersion 来源配置版本
     * @param sourceType 来源类型
     * @return 配置版本
     */
    ExperimentConfigVersion saveExperimentConfigVersion(String experimentId, ExperimentMetadata metadata,
                                                        String publishedBy, String publishComment,
                                                        Long sourceConfigVersion, String sourceType);

    /**
     * 查询实验配置版本列表
     *
     * @param experimentId 实验ID
     * @return 配置版本列表
     */
    List<ExperimentConfigVersion> listExperimentConfigVersions(String experimentId);

    /**
     * 查询指定实验配置版本
     *
     * @param experimentId 实验ID
     * @param configVersion 配置版本
     * @return 配置版本
     */
    Optional<ExperimentConfigVersion> getExperimentConfigVersion(String experimentId, long configVersion);

    /**
     * 保存实验配置草稿
     *
     * @param experimentId 实验ID
     * @param metadata 草稿配置
     * @param baseConfigVersion 基线配置版本
     * @param updatedBy 更新人
     * @param draftComment 草稿备注
     * @return 配置草稿
     */
    ExperimentConfigDraft saveExperimentConfigDraft(String experimentId, ExperimentMetadata metadata,
                                                    long baseConfigVersion, String updatedBy, String draftComment);

    /**
     * 查询实验配置草稿
     *
     * @param experimentId 实验ID
     * @return 配置草稿
     */
    Optional<ExperimentConfigDraft> getExperimentConfigDraft(String experimentId);

    /**
     * 删除实验配置草稿
     *
     * @param experimentId 实验ID
     */
    void deleteExperimentConfigDraft(String experimentId);

    /**
     * 保存实验配置草稿审批记录
     *
     * @param approval 草稿审批记录
     * @return 草稿审批记录
     */
    ExperimentConfigDraftApproval saveExperimentConfigDraftApproval(ExperimentConfigDraftApproval approval);

    /**
     * 查询实验最新配置草稿审批记录
     *
     * @param experimentId 实验ID
     * @return 草稿审批记录
     */
    Optional<ExperimentConfigDraftApproval> getCurrentExperimentConfigDraftApproval(String experimentId);

    /**
     * 查询实验配置草稿审批历史
     *
     * @param experimentId 实验ID
     * @return 草稿审批记录列表
     */
    List<ExperimentConfigDraftApproval> listExperimentConfigDraftApprovals(String experimentId);

    /**
     * 查询指定实验配置草稿审批记录
     *
     * @param experimentId 实验ID
     * @param draftVersion 草稿版本
     * @return 草稿审批记录
     */
    Optional<ExperimentConfigDraftApproval> getExperimentConfigDraftApproval(String experimentId, long draftVersion);

    /**
     * 更新实验配置草稿审批状态
     *
     * @param experimentId 实验ID
     * @param draftVersion 草稿版本
     * @param approvalStatus 审批状态
     * @param approvalOperator 审批操作人
     * @param approvalComment 审批备注
     * @return 草稿审批记录
     */
    Optional<ExperimentConfigDraftApproval> updateExperimentConfigDraftApprovalStatus(
            String experimentId, long draftVersion, ExperimentMetadata.ApprovalStatus approvalStatus,
            String approvalOperator, String approvalComment);

    // ── 流量分层管理 ──────────────────────────────────────────────────────────

    /**
     * 保存分层配置
     */
    void saveLayerConfig(String layerId, ExperimentLayer layer) throws Exception;

    /**
     * 获取分层配置
     */
    ExperimentLayer getLayerConfig(String layerId);

    /**
     * 删除分层配置
     */
    void deleteLayerConfig(String layerId) throws Exception;
}
