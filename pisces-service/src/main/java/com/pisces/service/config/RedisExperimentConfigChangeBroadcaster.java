package com.pisces.service.config;

import com.pisces.service.metrics.ConfigChangeBroadcastMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 基于 Redis Pub/Sub 的实验配置跨实例变更广播器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisExperimentConfigChangeBroadcaster implements ExperimentConfigChangeBroadcaster {

    private static final String PAYLOAD_SEPARATOR = "|";

    private static final int PAYLOAD_NODE_ID_INDEX = 0;

    private static final int PAYLOAD_EXPERIMENT_ID_INDEX = 1;

    private static final int EXPECTED_PAYLOAD_PART_COUNT = 2;

    private final StringRedisTemplate stringRedisTemplate;

    private final RedisMessageListenerContainer redisMessageListenerContainer;

    private final ConfigChangeBroadcastMetrics configChangeBroadcastMetrics;

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private final String nodeId = UUID.randomUUID().toString();

    @Value("${pisces.config-change.redis-broadcast-enabled:false}")
    private boolean redisBroadcastEnabled;

    @Value("${pisces.config-change.redis-channel:pisces:config-change}")
    private String redisChannel;

    @PostConstruct
    public void init() {
        configChangeBroadcastMetrics.recordEnabled(redisBroadcastEnabled);
        if (!redisBroadcastEnabled) {
            log.info("实验配置 Redis 跨实例广播未启用");
            return;
        }
        redisMessageListenerContainer.addMessageListener(this::handleMessage, new ChannelTopic(redisChannel));
        log.info("实验配置 Redis 跨实例广播已启用: channel={}", redisChannel);
    }

    @Override
    public void publishExperimentChange(String experimentId) {
        if (!redisBroadcastEnabled || !StringUtils.hasText(experimentId)) {
            configChangeBroadcastMetrics.recordPublished(ConfigChangeBroadcastMetrics.RESULT_SKIPPED);
            return;
        }
        try {
            stringRedisTemplate.convertAndSend(redisChannel, buildPayload(experimentId));
            configChangeBroadcastMetrics.recordPublished(ConfigChangeBroadcastMetrics.RESULT_SUCCESS);
        } catch (Exception exception) {
            configChangeBroadcastMetrics.recordPublished(ConfigChangeBroadcastMetrics.RESULT_ERROR);
            log.warn("实验配置 Redis 跨实例广播发送失败: experimentId={}", experimentId, exception);
        }
    }

    @Override
    public void addExperimentChangeListener(Consumer<String> listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
    }

    private void handleMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        RemoteExperimentConfigChange change = parsePayload(payload);
        if (change == null) {
            configChangeBroadcastMetrics.recordReceived(ConfigChangeBroadcastMetrics.RESULT_INVALID);
            return;
        }
        if (nodeId.equals(change.nodeId())) {
            configChangeBroadcastMetrics.recordReceived(ConfigChangeBroadcastMetrics.RESULT_IGNORED_SELF);
            return;
        }
        configChangeBroadcastMetrics.recordReceived(ConfigChangeBroadcastMetrics.RESULT_APPLIED);
        notifyListeners(change.experimentId());
    }

    private void notifyListeners(String experimentId) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(experimentId);
            } catch (Exception exception) {
                configChangeBroadcastMetrics.recordListenerError();
                log.warn("处理远端实验配置变更失败: experimentId={}", experimentId, exception);
            }
        }
    }

    private String buildPayload(String experimentId) {
        String encodedExperimentId = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(experimentId.getBytes(StandardCharsets.UTF_8));
        return nodeId + PAYLOAD_SEPARATOR + encodedExperimentId;
    }

    private RemoteExperimentConfigChange parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        String[] parts = payload.split("\\" + PAYLOAD_SEPARATOR, EXPECTED_PAYLOAD_PART_COUNT);
        if (parts.length < EXPECTED_PAYLOAD_PART_COUNT) {
            return new RemoteExperimentConfigChange("", payload);
        }
        try {
            String experimentId = new String(Base64.getUrlDecoder().decode(parts[PAYLOAD_EXPERIMENT_ID_INDEX]),
                    StandardCharsets.UTF_8);
            return new RemoteExperimentConfigChange(parts[PAYLOAD_NODE_ID_INDEX], experimentId);
        } catch (IllegalArgumentException exception) {
            log.warn("实验配置 Redis 跨实例广播载荷解析失败: payload={}, reason={}",
                    payload, exception.getMessage());
            return null;
        }
    }

    private record RemoteExperimentConfigChange(String nodeId, String experimentId) {
    }
}
