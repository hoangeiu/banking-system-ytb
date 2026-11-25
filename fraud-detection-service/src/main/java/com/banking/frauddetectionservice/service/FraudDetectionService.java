package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.model.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${fraud.max-transactions-per-minute}")
    private int maxTransactionsPerMinute;

    @Value("${fraud.suspicious-amount-multiplier}")
    private double suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;

    private static final String VERIFICATION_REQUIRED_TOPIC = "verification.required";
    private static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud.check.clean";

    public void checkTransaction(Map<String, Object> payload) {
        String transactionId = (String) payload.get("transactionId");
        String accountNumber = (String) payload.get("senderAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        // Fetch real balance from `account-service`
        BigDecimal senderBalance = accountServiceClient.getBalance(accountNumber);

        FraudCheckResult fraudCheckResult = performFraudChecks(accountNumber, amount, senderBalance);

        if (fraudCheckResult.isFraud()) {
            log.info("Suspicious activity detected - account: {} reason: {} - requesting OTP verification",
                    transactionId, fraudCheckResult.getReason());

            Map<String, Object> verificationEvent = new HashMap<>();
            verificationEvent.put("transactionId", transactionId);
            verificationEvent.put("reason", fraudCheckResult.getReason());
            verificationEvent.put("accountNumber", accountNumber);
            verificationEvent.put("amount", amount);

            // Send verification event to Kafka topic with topic `verification.required`
            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC, verificationEvent);
        } else {
            log.info("Transaction approved");

            Map<String, Object> approvedEvent = new HashMap<>();
            approvedEvent.put("transactionId", transactionId);
            approvedEvent.put("isFraud", false);
            approvedEvent.put("reason", null);

            // Send approved event to Kafka topic with topic `fraud.check.clean`
            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC, approvedEvent);
        }
    }

    private FraudCheckResult performFraudChecks(String accountNumber, BigDecimal amount, BigDecimal senderBalance) {
        // Pattern 1: Velocity Check
        if (isVelocityExceeded(accountNumber)) {
            return new FraudCheckResult(true, "Velocity limit exceeded - Too many transactions in 60 seconds");
        }

        // Pattern 2: Amount check
        if (isAmountSuspicious(accountNumber, amount)) {
            return new FraudCheckResult(true, "Unusual transaction amount - exceed 3x your average");
        }

        // Pattern 3: Balance check
        if (senderBalance.compareTo(BigDecimal.ZERO) > 0 && isBalanceCheckFailed(senderBalance, amount)) {
            return new FraudCheckResult(true, "Transaction exceed 90% of account balance");
        }

        return new FraudCheckResult(false, null);
    }

    private boolean isBalanceCheckFailed(BigDecimal senderBalance, BigDecimal amount) {
        BigDecimal maxAllowed = senderBalance.multiply(BigDecimal.valueOf(maxBalancePercentage));

        log.info("Balance check - amount: {}, maxAllowed: {} , suspicious: {}", amount, maxAllowed,
                amount.compareTo(maxAllowed) > 0);

        return amount.compareTo(maxAllowed) > 0;
    }

    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount) {
        String avgKey = "fraud:avg_amount" + accountNumber;
        String avtStr = redisTemplate.opsForValue().get(avgKey);
        if (avtStr == null) {
            redisTemplate.opsForValue().set(avgKey, amount.toString());
            return false;
        }
        BigDecimal avgAmount = new BigDecimal(avtStr);
        BigDecimal threshold = avgAmount.multiply(BigDecimal.valueOf(suspiciousAmountMultiplier));

        // update running average
        BigDecimal newAvgAmount = avgAmount.add(amount).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        redisTemplate.opsForValue().set(avgKey, newAvgAmount.toString());

        log.info("Amount check for account {} - amount: {}, threshold: {}, suspicious: {}", accountNumber, amount,
                threshold, amount.compareTo(threshold) > 0);

        return amount.compareTo(threshold) > 0;
    }


    private boolean isVelocityExceeded(String accountNumber) {
        String key = "fraud:velocity" + accountNumber;
        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }

        log.info("Velocity check for account {} - count: {}/{}", accountNumber, count, maxTransactionsPerMinute);

        return count != null && count > maxTransactionsPerMinute;
    }
}
