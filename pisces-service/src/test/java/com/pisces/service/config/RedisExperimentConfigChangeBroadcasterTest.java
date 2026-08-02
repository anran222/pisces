package com.pisces.service.config;

import com.pisces.service.metrics.ConfigChangeBroadcastMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisExperimentConfigChangeBroadcasterTest {

    private static final String TEST_CHANNEL = "pisces:test:config-change";

    private static final String PUBLISHED_METRIC = "pisces.config.change.broadcast.published";

    private static final String RECEIVED_METRIC = "pisces.config.change.broadcast.received";

    private static final String LISTENER_ERRORS_METRIC = "pisces.config.change.broadcast.listener.errors";

    @Test
    void shouldIgnoreSelfMessageAndDispatchRemoteMessage() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer listenerContainer = mock(RedisMessageListenerContainer.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisExperimentConfigChangeBroadcaster broadcaster =
                new RedisExperimentConfigChangeBroadcaster(stringRedisTemplate, listenerContainer,
                        buildMetrics(meterRegistry));
        ReflectionTestUtils.setField(broadcaster, "redisBroadcastEnabled", true);
        ReflectionTestUtils.setField(broadcaster, "redisChannel", TEST_CHANNEL);
        List<String> receivedExperimentIds = new ArrayList<>();
        broadcaster.addExperimentChangeListener(receivedExperimentIds::add);

        broadcaster.init();
        broadcaster.publishExperimentChange("exp_config_repo_001");

        MessageListener listener = captureMessageListener(listenerContainer);
        String selfPayload = capturePublishedPayload(stringRedisTemplate);
        listener.onMessage(message(selfPayload), null);
        listener.onMessage(message(buildRemotePayload("exp_config_repo_001")), null);

        assertThat(receivedExperimentIds).containsExactly("exp_config_repo_001");
        assertThat(counter(meterRegistry, PUBLISHED_METRIC, ConfigChangeBroadcastMetrics.RESULT_SUCCESS))
                .isEqualTo(1D);
        assertThat(counter(meterRegistry, RECEIVED_METRIC, ConfigChangeBroadcastMetrics.RESULT_IGNORED_SELF))
                .isEqualTo(1D);
        assertThat(counter(meterRegistry, RECEIVED_METRIC, ConfigChangeBroadcastMetrics.RESULT_APPLIED))
                .isEqualTo(1D);
    }

    @Test
    void shouldNotRegisterRedisListenerWhenDisabled() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer listenerContainer = mock(RedisMessageListenerContainer.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisExperimentConfigChangeBroadcaster broadcaster =
                new RedisExperimentConfigChangeBroadcaster(stringRedisTemplate, listenerContainer,
                        buildMetrics(meterRegistry));

        broadcaster.init();
        broadcaster.publishExperimentChange("exp_config_repo_001");

        verifyNoInteractions(listenerContainer, stringRedisTemplate);
        assertThat(meterRegistry.find("pisces.config.change.broadcast.enabled").gauge().value()).isZero();
        assertThat(counter(meterRegistry, PUBLISHED_METRIC, ConfigChangeBroadcastMetrics.RESULT_SKIPPED))
                .isEqualTo(1D);
    }

    @Test
    void shouldRecordInvalidMessageAndListenerError() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer listenerContainer = mock(RedisMessageListenerContainer.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisExperimentConfigChangeBroadcaster broadcaster =
                new RedisExperimentConfigChangeBroadcaster(stringRedisTemplate, listenerContainer,
                        buildMetrics(meterRegistry));
        ReflectionTestUtils.setField(broadcaster, "redisBroadcastEnabled", true);
        ReflectionTestUtils.setField(broadcaster, "redisChannel", TEST_CHANNEL);
        broadcaster.addExperimentChangeListener(experimentId -> {
            throw new IllegalStateException("listener failed");
        });

        broadcaster.init();
        MessageListener listener = captureMessageListener(listenerContainer);
        listener.onMessage(message("remote-node|not-base64@@"), null);
        listener.onMessage(message(buildRemotePayload("exp_config_repo_001")), null);

        assertThat(counter(meterRegistry, RECEIVED_METRIC, ConfigChangeBroadcastMetrics.RESULT_INVALID))
                .isEqualTo(1D);
        assertThat(counter(meterRegistry, RECEIVED_METRIC, ConfigChangeBroadcastMetrics.RESULT_APPLIED))
                .isEqualTo(1D);
        assertThat(meterRegistry.find(LISTENER_ERRORS_METRIC).counter().count()).isEqualTo(1D);
    }

    private MessageListener captureMessageListener(RedisMessageListenerContainer listenerContainer) {
        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        ArgumentCaptor<Topic> topicCaptor = ArgumentCaptor.forClass(Topic.class);
        verify(listenerContainer).addMessageListener(listenerCaptor.capture(), topicCaptor.capture());
        assertThat(topicCaptor.getValue().getTopic()).isEqualTo(TEST_CHANNEL);
        return listenerCaptor.getValue();
    }

    private String capturePublishedPayload(StringRedisTemplate stringRedisTemplate) {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq(TEST_CHANNEL), payloadCaptor.capture());
        return payloadCaptor.getValue();
    }

    private String buildRemotePayload(String experimentId) {
        String encodedExperimentId = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(experimentId.getBytes(StandardCharsets.UTF_8));
        return "remote-node|" + encodedExperimentId;
    }

    private Message message(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] channel = TEST_CHANNEL.getBytes(StandardCharsets.UTF_8);
        return new Message() {
            @Override
            public byte[] getBody() {
                return body;
            }

            @Override
            public byte[] getChannel() {
                return channel;
            }
        };
    }

    private ConfigChangeBroadcastMetrics buildMetrics(SimpleMeterRegistry meterRegistry) {
        ConfigChangeBroadcastMetrics metrics = new ConfigChangeBroadcastMetrics(meterRegistry);
        metrics.init();
        return metrics;
    }

    private double counter(SimpleMeterRegistry meterRegistry, String metricName, String result) {
        return meterRegistry.find(metricName)
                .tag("result", result)
                .counter()
                .count();
    }
}
