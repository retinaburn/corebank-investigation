package corebank.output;

import corebank.BatchLocks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Exports a committed snapshot; never posts transactions or reads live balances. */
public final class BalanceExporter {
    /** Owns the supplied dedicated connection's transaction. Existing identical files are no-ops. */
    public Path export(Connection c, Path directory, LocalDate date) throws SQLException, IOException {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999)
            throw new IllegalArgumentException("Batch date must have a four-digit positive year");
        if (!c.getAutoCommit()) throw new IllegalArgumentException("Output requires auto-commit enabled");
        Path temporary = null;
        c.setAutoCommit(false);
        try {
            // Serialize cooperating exporters for a date, including the publication step.
            try (var s = c.prepareStatement("SELECT pg_advisory_xact_lock(?,?)")) {
                s.setInt(1, BatchLocks.OUTPUT_LOCK_NAMESPACE); s.setInt(2, BatchLocks.dateKey(date)); s.execute();
            }
            long expected;
            try (var s = c.prepareStatement("SELECT s.record_count FROM core_output.batch_snapshot s JOIN core.batch_run r USING(batch_run_id,batch_date) WHERE s.batch_date=? AND r.status='COMPLETED'")) {
                s.setObject(1, date);
                try (var rs = s.executeQuery()) {
                    if (!rs.next()) throw new SQLException("No completed balance snapshot for " + date + "; unfinished batches and batches completed before output migration cannot be exported");
                    expected = rs.getLong(1);
                }
            }
            Files.createDirectories(directory);
            Path target = directory.resolve("balance_" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + ".dat");
            temporary = Files.createTempFile(directory, ".balance_" + date + "_", ".tmp");
            long count = 0;
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
                 var s = c.prepareStatement("SELECT account_id,balance FROM core_output.balance WHERE batch_date=? ORDER BY account_id")) {
                s.setObject(1, date); s.setFetchSize(500);
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        writer.write(String.format(Locale.ROOT, "%19d%20d\n", rs.getLong(1), rs.getLong(2)));
                        count++;
                    }
                }
            }
            if (count != expected) throw new SQLException("Snapshot record count mismatch for " + date);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(temporary, target) != -1)
                    throw new IOException("Output already exists with different contents or is not a regular file: " + target);
                return target;
            }
            // Same-directory atomic publication; unsupported filesystems fail safely.
            // All application writers must use this exporter; external writers are not locked.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            try { if (temporary != null) Files.deleteIfExists(temporary); }
            finally {
                try { c.rollback(); }
                finally { c.setAutoCommit(true); }
            }
        }
    }
}
