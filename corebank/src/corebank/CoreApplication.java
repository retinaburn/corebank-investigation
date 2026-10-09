package corebank;

import java.time.LocalDate;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import corebank.BankingRecords.AccountType;
import corebank.BankingRecords.TransactionType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(Config.class)
public class CoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    @Bean
    CommandLineRunner startup(Config config) {
        return args -> {
            log.info("Welcome to the Core Banking System!");
            log.info("Data directories: input={}, output={}, error={}",
                    config.inputDirectory(), config.outputDirectory(), config.errorDirectory());
            log.info("Customer: {}", new BankingRecords.Customer(1L, "John", "Doe", "123 Main St", "Anytown", "ON", "A1B 2C3", "Canada"));
            log.info("Account: {}", new BankingRecords.Account(1L, LocalDate.now(), null, AccountType.CHECKING));
            log.info("Transaction: {}", new BankingRecords.Transaction(1L, 1L, TransactionType.CR, 1000));
            log.info("Relationship: {}", new BankingRecords.Relationship(1L, 1L, BankingRecords.RelationshipType.PRIMARY));
        };
    }
}
