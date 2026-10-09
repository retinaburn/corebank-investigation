package corebank.readers;

import java.text.Normalizer;
import java.util.Objects;
import org.springframework.batch.item.file.transform.DefaultFieldSet;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.LineTokenizer;

/** Fixed widths count NFC-normalized Unicode code points, never UTF-16 units. */
public final class CodePointLineTokenizer implements LineTokenizer {
    private final String[] names;
    private final int[] widths;
    private final int length;

    public CodePointLineTokenizer(String[] names, int... widths) {
        if (names.length == 0 || names.length != widths.length)
            throw new IllegalArgumentException("One name is required per field width");
        this.names = names.clone();
        this.widths = widths.clone();
        int total = 0;
        for (int width : widths) {
            if (width <= 0) throw new IllegalArgumentException("Widths must be positive");
            total = Math.addExact(total, width);
        }
        this.length = total;
    }

    @Override
    public FieldSet tokenize(String input) {
        String line = Normalizer.normalize(Objects.requireNonNull(input), Normalizer.Form.NFC);
        if (line.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0xFEFF
                || cp == 0x2028 || cp == 0x2029 || (cp >= 0xD800 && cp <= 0xDFFF)))
            throw new IllegalArgumentException("Control characters, BOMs and invalid surrogates are forbidden");
        int actual = line.codePointCount(0, line.length());
        if (actual != length)
            throw new IllegalArgumentException("Expected " + length + " code points, got " + actual);
        String[] values = new String[widths.length];
        int start = 0;
        for (int i = 0; i < widths.length; i++) {
            int end = line.offsetByCodePoints(start, widths[i]);
            values[i] = line.substring(start, end);
            start = end;
        }
        return new DefaultFieldSet(values, names);
    }
}
