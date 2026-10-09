package corebank.readers;

import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import corebank.BankingRecords.Account;
import corebank.BankingRecords.AccountType;

/** Account layout: ID 19, start date 10, optional end date 10, type 8. */
public final class AccountFileReader {
    private AccountFileReader() {}

    public static FlatFileItemReader<Account> create(Path file) {
        var tokenizer = new CodePointLineTokenizer(
            new String[] {"accountId", "startDate", "endDate", "accountType"}, 19, 10, 10, 8);
        var reader = new FlatFileItemReaderBuilder<Account>()
            .name("accountFileReader").resource(new FileSystemResource(file))
            .strict(true).comments(new String[0]).lineTokenizer(tokenizer)
            .fieldSetMapper(f -> new Account(
                FileReaderSupport.positiveId(f.readString("accountId")),
                date(f.readRawString("startDate")),
                f.readRawString("endDate").equals(" ".repeat(10)) ? null : date(f.readRawString("endDate")),
                AccountType.valueOf(f.readString("accountType"))))
            .build();
        FileReaderSupport.configure(reader);
        return reader;
    }

    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Date must use YYYY-MM-DD");
        return LocalDate.parse(value);
    }
}
