package org.example.quantapi.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${quant.kafka.signal-topic}")
    private String signalTopic;

    @Bean
    public NewTopic signalTopic() {
        return TopicBuilder.name(signalTopic)
                .partitions(4)
                .replicas(1)
                .build();
    }
}
