package corebank;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileParseException;
import static org.junit.jupiter.api.Assertions.*;

public class AccountFileReaderTest {
    @TempDir Path directory;
    private static String line(String id, String start, String end, String type) {
        return " ".repeat(19-id.length()) + id + start + end + type + " ".repeat(8-type.length());
    }
    @ParameterizedTest @ValueSource(strings={"SAVINGS", "CHECKING"})
    void readsOpenAccount(String type) throws Exception {
        Path file=directory.resolve("account.dat");
        Files.writeString(file, line("9223372036854775807", "2024-02-29", " ".repeat(10), type)+"\n");
        var reader=AccountFileReader.create(file);
        try {
            reader.open(new ExecutionContext());
            var account=reader.read();
            assertEquals(Long.MAX_VALUE, account.accountId());
            assertEquals(LocalDate.of(2024,2,29),account.startDate());
            assertNull(account.endDate());
            assertEquals(type,account.accountType().name());
            assertNull(reader.read());
        } finally { reader.close(); }
    }
    @Test void readsEndDate() throws Exception {
        Path file=directory.resolve("closed.dat");
        Files.writeString(file,line("1","2024-01-01","2026-10-09","SAVINGS")+"\n");
        var reader=AccountFileReader.create(file);
        try { reader.open(new ExecutionContext()); assertEquals(LocalDate.of(2026,10,9),reader.read().endDate()); }
        finally { reader.close(); }
    }
    @ParameterizedTest @ValueSource(strings={"zero","negative","overflow","type","date","missingStart","badEnd","short","long","crlf"})
    void rejectsInvalidAccount(String kind) throws Exception {
        String record=switch(kind) {
            case "zero" -> line("0","2024-01-01"," ".repeat(10),"SAVINGS");
            case "negative" -> line("-1","2024-01-01"," ".repeat(10),"SAVINGS");
            case "overflow" -> line("9223372036854775808","2024-01-01"," ".repeat(10),"SAVINGS");
            case "type" -> line("1","2024-01-01"," ".repeat(10),"A1");
            case "date" -> line("1","2023-02-29"," ".repeat(10),"SAVINGS");
            case "missingStart" -> line("1"," ".repeat(10)," ".repeat(10),"SAVINGS");
            case "badEnd" -> line("1","2024-01-01","2026-13-01","SAVINGS");
            default -> line("1","2024-01-01"," ".repeat(10),"SAVINGS");
        };
        if(kind.equals("short"))record=record.substring(1);
        if(kind.equals("long"))record+=" ";
        if(kind.equals("crlf"))record+="\r";
        Path file=directory.resolve("invalid-account.dat");
        Files.writeString(file,record+"\n");
        var reader=AccountFileReader.create(file);
        try {
            reader.open(new ExecutionContext());
            var error=assertThrows(FlatFileParseException.class,reader::read);
            assertEquals(1,error.getLineNumber());
            assertTrue(error.getMessage().contains("invalid-account.dat"));
        } finally { reader.close(); }
    }
}
