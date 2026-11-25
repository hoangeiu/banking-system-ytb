package com.banking.frauddetectionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionEventConsumer {
    private final FraudDetectionService fraudDetectionService;

    private static final String FRAUD_DETECTION_GROUP = "fraud-detection-group";
    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";

    /**
     * Listens to transaction.initiated topic
     * Every transaction goes through fraud check before completing
     *
     * @param payload payload
     */
    @KafkaListener(topics = TRANSACTION_INITIATED_TOPIC, groupId = FRAUD_DETECTION_GROUP)
    public void consumeTransactionInitiated(@Payload Map<String, Object> payload) {
        log.info("Received transaction for fraud check: {}", payload.get("transactionId"));

        try {
            fraudDetectionService.checkTransaction(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
