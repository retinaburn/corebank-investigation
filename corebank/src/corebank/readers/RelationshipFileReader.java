package corebank.readers;

import java.nio.file.Path;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import corebank.BankingRecords.Relationship;
import corebank.BankingRecords.RelationshipType;

/** Reads fixed-width Relationship input; reference and duplicate checks belong to staging validation. */
public final class RelationshipFileReader {
    private RelationshipFileReader() {}

    public static FlatFileItemReader<Relationship> create(Path file) {
        var tokenizer = new CodePointLineTokenizer(
            new String[] {"accountId", "customerId", "type"}, 19, 19, 9);
        var reader = new FlatFileItemReaderBuilder<Relationship>()
            .name("relationshipFileReader").resource(new FileSystemResource(file))
            .strict(true).comments(new String[0]).lineTokenizer(tokenizer)
            .fieldSetMapper(f -> new Relationship(
                FileReaderSupport.positiveId(f.readString("accountId")),
                FileReaderSupport.positiveId(f.readString("customerId")),
                RelationshipType.valueOf(f.readString("type"))))
            .build();
        FileReaderSupport.configure(reader);
        return reader;
    }
}
