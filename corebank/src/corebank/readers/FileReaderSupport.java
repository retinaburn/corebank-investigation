package corebank.readers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import org.springframework.batch.item.file.FlatFileItemReader;

final class FileReaderSupport {
    private FileReaderSupport() {}
    static <T> void configure(FlatFileItemReader<T> reader) {
        // BufferedReader.readLine normally accepts CRLF; preserve CR for the tokenizer
        // to reject it. REPORT prevents malformed UTF-8 being silently replaced.
        reader.setBufferedReaderFactory((resource, encoding) -> new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            @Override
            public String readLine() throws IOException {
                StringBuilder line = new StringBuilder();
                int ch;
                while ((ch = read()) != -1) {
                    if (ch == '\n') return line.toString();
                    line.append((char) ch);
                }
                return line.isEmpty() ? null : line.toString();
            }
        });
    }
    static long positiveId(String value) {
        if (!value.matches("[0-9]+"))
            throw new IllegalArgumentException("ID must contain unsigned decimal digits");
        long id = Long.parseLong(value);
        if (id == 0) throw new IllegalArgumentException("ID must be greater than zero");
        return id;
    }
}
