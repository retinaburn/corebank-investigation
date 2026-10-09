package corebank;

import java.nio.file.Path;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import corebank.BankingRecords.Customer;

/** Creates a fresh reader per file. Call open, read until null, and close. */
public final class CustomerFileReader {
    private CustomerFileReader() {}

    public static FlatFileItemReader<Customer> create(Path file) {
        var tokenizer = new CodePointLineTokenizer(new String[] {
            "customerId", "firstName", "lastName", "addressLine1", "city",
            "province", "postalCode", "country"
        }, 19, 10, 10, 20, 10, 2, 7, 10);
        var reader = new FlatFileItemReaderBuilder<Customer>()
            .name("customerFileReader")
            .resource(new FileSystemResource(file))
            .strict(true)
            .comments(new String[0])
            .lineTokenizer(tokenizer)
            .fieldSetMapper(f -> new Customer(
                FileReaderSupport.positiveId(f.readString("customerId")),
                f.readString("firstName"), f.readString("lastName"),
                f.readString("addressLine1"), f.readString("city"),
                f.readString("province"), f.readString("postalCode"), f.readString("country")))
            .build();
        FileReaderSupport.configure(reader);
        return reader;
    }
}
