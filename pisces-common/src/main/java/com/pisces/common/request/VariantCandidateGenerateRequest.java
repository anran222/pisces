package com.pisces.common.request;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 候选变体生成请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 12:03
 */
@Data
public class VariantCandidateGenerateRequest {

    /**
     * 变体类型
     */
    private String variantType;

    /**
     * 生成目标
     */
    private String goal;

    /**
     * 目标受众
     */
    private String audience;

    /**
     * 约束条件
     */
    private List<String> constraints;

    /**
     * 生成数量
     */
    private Integer count;

    /**
     * 上下文信息
     */
    private Map<String, Object> sourceContext;

    /**
     * 本轮方案修改要求
     */
    private String refinementInstruction;

    /**
     * 当前候选方案
     */
    private List<String> currentVariants;

    /**
     * 最近对话记录
     */
    private List<ConversationMessage> conversation;

    /**
     * 方案修订对话消息
     */
    @Data
    public static class ConversationMessage {

        /**
         * 消息角色
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;
    }
}
