package com.banking.transactionservice.entity;

/**
 * Transaction lifecycle flow
 *
 * PENDING -> PROGRESS -> COMPLETED (clean transaction)
 *                     -> PENDING_VERIFICATION (suspicious transaction)
 *                                -> COMPLETED (verified transaction)
 *                                -> FLAGGED (SAGA REFUND)
 *                     -> FAILED
 *                     -> FLAGGED
 */

public enum TransactionStatus {
    PENDING,
    PROCESSING,
    PENDING_VERIFICATION,
    COMPLETED,
    FLAGGED,
    FAILED
}
