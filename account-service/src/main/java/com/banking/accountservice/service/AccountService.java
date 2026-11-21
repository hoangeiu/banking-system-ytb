package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int MAX_GENERATION_ATTEMPTS = 10;

    // TODO: Handle race condition when concurrent requests generate the same account number.
    // Consider adding Spring Retry (@Retryable on DataIntegrityViolationException) or using DB Sequence / Snowflake ID.
    public AccountResponse createAccount(@Valid CreateAccountRequest request) {
        log.info("BEGIN - Creating account for user: {}", request.getEmail());

        if(accountRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Account already exists for email: " + request.getEmail());
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(request.getEmail());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVINGS
                        ? new BigDecimal(100000)
                        : new BigDecimal(500000));

        Account savedAccount = accountRepository.save(account);
        log.info("END - Account created for user: {}, account number: {}", request.getEmail(), savedAccount.getAccountNumber());

        return mapToResponse(savedAccount);
    }

    /**
     * Get account by account number
     * @param accountNumber accountNumber
     * @return AccountResponse
     */
    public AccountResponse getAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found for account number: " + accountNumber));

        return mapToResponse(account);
    }

    /**
     * Get account balance by account number
     * @param accountNumber accountNumber
     * @return 2-digit precision, 2-digit scale
     */
    public BigDecimal getBalance(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found for account number: " + accountNumber));

        return account.getBalance();
    }

    /**
     * Block account - called by Fraud detection service via Kafka
     * @param accountNumber accountNumber
     */
    public void blockAccount(String accountNumber) {
        log.info("Blocking account for account number: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found for account number: " + accountNumber));
        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
    }

    /**
     * Deduct balance from the sender account
     * @param accountNumber accountNumber
     * @param amount amount to credit
     */
    public void deductBalance(String accountNumber, BigDecimal amount) {
        log.info("BEGIN - Deducting balance for account number: {} amount: {}", accountNumber, amount);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found for account number: " + accountNumber));

        if(account.getStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Account is not active" + accountNumber);
        }

        if(account.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient balance for account: " + accountNumber);
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.info("END - Balance deducted for account number: {} amount: {}", accountNumber, amount);
    }

    /**
     * Credit balance called by Transaction Service via Kafka
     * @param accountNumber accountNumber
     * @param amount amount to credit
     */
    public void creditBalance(String accountNumber, BigDecimal amount) {
        log.info("BEGIN - Crediting balance for account number: {} amount: {}", accountNumber, amount);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found for account number: " + accountNumber));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.info("END - Balance credited for account number: {} amount: {}", accountNumber, amount);
    }



    /**
     * Generates a unique 12-digit account number
     * @return unique account number
     */
    private String generateAccountNumber() {
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            long number = secureRandom.nextLong(1_000_000_000_000L);
            String accountNumber = String.format("%012d", number);

            if (!accountRepository.existsByAccountNumber(accountNumber)) {
                return accountNumber;
            }
            log.warn("Account number collision detected on attempt {}", attempt);
        }
        throw new IllegalStateException("Failed to generate unique account number after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    // TODO: Implement Mapper for mapping between Account and AccountResponse
    private AccountResponse mapToResponse(Account savedAccount) {
        AccountResponse accountResponse = new AccountResponse();
        accountResponse.setAccountNumber(savedAccount.getAccountNumber());
        accountResponse.setAccountHolderName(savedAccount.getAccountHolderName());
        accountResponse.setEmail(savedAccount.getEmail());
        accountResponse.setPhone(savedAccount.getPhone());
        accountResponse.setAccountType(savedAccount.getAccountType());
        accountResponse.setStatus(savedAccount.getStatus());
        accountResponse.setBalance(savedAccount.getBalance());
        accountResponse.setDailyTransactionLimit(savedAccount.getDailyTransactionLimit());
        accountResponse.setCreatedAt(savedAccount.getCreatedAt());
        return accountResponse;
    }
}
