package com.epam.notifications.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
@EnableKafkaRetryTopic
public class KafkaConfig {

    @Bean
    @ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "true")
    public NewTopic notificationCreatedTopic(
            @Value("${notification.kafka.topic:notifications.created}") String topicName,
            @Value("${notification.kafka.partitions:6}") int partitions,
            @Value("${notification.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(topicName)
                .partitions(Math.max(1, partitions))
                .replicas(Math.max((short) 1, replicas))
                .build();
    }
}
