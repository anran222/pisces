package com.pisces.service.zookeeper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZookeeperClientTest {

    @Test
    void initShouldSkipClientCreationWhenZookeeperIsDisabled() {
        ZookeeperConfig config = new ZookeeperConfig();
        config.setEnabled(false);
        ZookeeperClient client = new ZookeeperClient(config, new JsonUtil(new ObjectMapper()));

        client.init();

        assertThat(client.isConnected()).isFalse();
        assertThat(client.getClient()).isNull();
    }
}
