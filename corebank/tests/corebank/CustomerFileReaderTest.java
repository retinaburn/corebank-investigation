package corebank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileParseException;



public class CustomerFileReaderTest {
    @TempDir Path directory;

    // Production IDs occupy 19 positions.
    private static String pad(String s, int n) {
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        return s + " ".repeat(n - s.codePointCount(0, s.length()));
    }
    private static String line(String name) {
        return " ".repeat(18) + "1" + pad(name, 10) + pad("Doe", 10) + pad("123 Main St", 20)
            + pad("Toronto", 10) + "ON" + "A1B 2C3" + pad("Canada", 10);
    }
    @Test void readsCustomerAndEndOfFile() throws Exception {
        Path file = directory.resolve("customer.dat");
        Files.writeString(file, line("José") + "\n");
        var reader = CustomerFileReader.create(file);
        try {
            reader.open(new ExecutionContext());
            var customer = reader.read();
            assertEquals(1L, customer.customerId());
            assertEquals("José", customer.firstName());
            assertEquals("123 Main St", customer.addressLine1());
            assertEquals("Toronto", customer.city());
            assertEquals("ON", customer.province());
            assertEquals("A1B 2C3", customer.postalCode());
            assertEquals("Canada", customer.country());
            assertNull(reader.read());
        } finally { reader.close(); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"𐐀Name", "José"})
    void handlesSupplementaryAndDecomposedCharacters(String name) throws Exception {
        Path file = directory.resolve("unicode.dat");
        String input = line(name);
        if (name.contains("\u0301")) input = input.replace("José", "José");
        Files.writeString(file, input + "\n");
        var reader = CustomerFileReader.create(file);
        try {
            reader.open(new ExecutionContext());
            var customer = reader.read();
            assertEquals(Normalizer.normalize(name, Normalizer.Form.NFC), customer.firstName());
            assertEquals("Doe", customer.lastName());
            assertEquals("Canada", customer.country());
        } finally { reader.close(); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"short", "long", "control", "bom", "crlf", "badId", "overflow", "negative", "zero", "blank"})
    void rejectsMalformedRecordsWithFileAndLine(String kind) throws Exception {
        String good = line("John");
        String bad = switch (kind) {
            case "short" -> good.substring(1);
            case "long" -> good + " ";
            case "control" -> good.replace("John", "Jo\tn");
            case "bom" -> "\uFEFF" + good;
            case "crlf" -> good + "\r";
            case "badId" -> " ".repeat(16) + "abc" + good.substring(19);
            case "overflow" -> "9223372036854775808" + good.substring(19);
            case "negative" -> " ".repeat(17) + "-1" + good.substring(19);
            case "zero" -> " ".repeat(18) + "0" + good.substring(19);
            default -> "";
        };
        Path file = directory.resolve("invalid.dat");
        String first = good;
        Files.writeString(file, first + "\n" + bad + "\n");
        var reader = CustomerFileReader.create(file);
        try {
            reader.open(new ExecutionContext());
            assertNotNull(reader.read());
            var error = assertThrows(FlatFileParseException.class, reader::read);
            assertEquals(2, error.getLineNumber());
            assertTrue(error.getMessage().contains("invalid.dat"));
        } finally { reader.close(); }
    }
    @Test void acceptsLargestLongId() throws Exception {
        Path file = directory.resolve("max.dat");
        Files.writeString(file, "9223372036854775807" + line("John").substring(19) + "\n");
        var reader = CustomerFileReader.create(file);
        try {
            reader.open(new ExecutionContext());
            assertEquals(Long.MAX_VALUE, reader.read().customerId());
        } finally { reader.close(); }
    }
    @Test void acceptsEmptyFile() throws Exception {
        Path file = Files.createFile(directory.resolve("empty.dat"));
        var reader = CustomerFileReader.create(file);
        try { reader.open(new ExecutionContext()); assertNull(reader.read()); }
        finally { reader.close(); }
    }
    @Test void rejectsMissingFile() {
        var reader = CustomerFileReader.create(directory.resolve("missing.dat"));
        try { assertThrows(Exception.class, () -> reader.open(new ExecutionContext())); }
        finally { reader.close(); }
    }
    @Test void rejectsMalformedUtf8() throws Exception {
        Path file = directory.resolve("bad-utf8.dat");
        Files.write(file, new byte[] {(byte)0xc3, (byte)0x28, 10});
        var reader = CustomerFileReader.create(file);
        try { reader.open(new ExecutionContext()); assertThrows(Exception.class, reader::read); }
        finally { reader.close(); }
    }
}
