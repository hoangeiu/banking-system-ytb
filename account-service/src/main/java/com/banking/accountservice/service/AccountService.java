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
    private static SecureRandom random = new SecureRandom();

    public AccountResponse createAccount(@Valid CreateAccountRequest request) {
        log.info("BEGIN - Creating account for user: {}", request.getEmail());

        if(accountRepository.existByEmail(request.getEmail())) {
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

    // 12-digit unique account number generator
    private String generateAccountNumber() {
        String accountNumber;
        do {
            long number = random.nextLong(1_000_000_000_000L);
            accountNumber = String.format("%012d", number);
        } while (accountRepository.existByAccountNumber(accountNumber));
        return accountNumber;
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
