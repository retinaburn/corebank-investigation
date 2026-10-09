package corebank.batch;

import corebank.BatchLocks;
import corebank.Config;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Polls final trigger names; files must be fully published before their trigger. */
public final class TriggerMonitor {
    private static final Logger log = LoggerFactory.getLogger(TriggerMonitor.class);
    private static final Pattern READY = Pattern.compile("batch_([0-9]{8})\\.trg");
    private static final Pattern ACTIVE = Pattern.compile("batch_([0-9]{8})\\.INPROGRESS\\.trg");
    private static final Pattern ERROR = Pattern.compile("batch_([0-9]{8})\\.ERROR\\.trg");
    @FunctionalInterface public interface BatchAction { void run(LocalDate date) throws Exception; }
    private final Config config;
    private final BatchAction action;

    public TriggerMonitor(Config config) {
        this(config, date -> new BatchJobRunner().run(config, date));
    }

    public TriggerMonitor(Config config, BatchAction action) {
        this.config = config;
        this.action = action;
    }

    public void run() throws Exception {
        Files.createDirectories(config.inputDirectory());
        Files.createDirectories(config.outputDirectory());
        Files.createDirectories(config.errorDirectory());
        var db = config.database();
        // Hold one monitor lease across scanning, claiming, executing and finalization.
        try (var guard = DriverManager.getConnection(db.url(), db.username(), db.password())) {
            try (var s = guard.prepareStatement("SELECT pg_try_advisory_lock(?,0)")) {
                s.setInt(1, BatchLocks.MONITOR_LOCK_NAMESPACE);
                try (var rs = s.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) throw new IllegalStateException("Another trigger monitor is active");
                }
            }
            var stopping = new AtomicBoolean();
            Thread worker = Thread.currentThread();
            Thread shutdown = new Thread(() -> {
                stopping.set(true);
                // Give an in-flight job a chance to finish and finalize its marker.
                // Forced termination can still leave INPROGRESS and requires investigation.
                try { worker.join(25_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "trigger-monitor-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                log.info("Monitoring {} for batch_YYYYMMDD.trg (poll interval: 1 second)", config.inputDirectory());
                while (!stopping.get()) {
                    if (!guard.isValid(5)) throw new IllegalStateException("Trigger monitor database lease was lost");
                    if (!processNext()) Thread.sleep(1000);
                }
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException shuttingDown) { /* Shutdown hook is already running. */ }
            }
        }
    }

    /** One scan under the monitor lease. Exposed for deterministic lifecycle tests. */
    public boolean processNext() throws Exception {
        Path ready;
        try (var files = Files.list(config.inputDirectory())) {
            var entries = files.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (var p : entries) {
                if (ACTIVE.matcher(p.getFileName().toString()).matches())
                    throw new IllegalStateException("Unresolved INPROGRESS trigger requires investigation: " + p);
            }
            ready = entries.stream().filter(p -> READY.matcher(p.getFileName().toString()).matches()).findFirst().orElse(null);
        }
        if (ready == null) return false;
        String stem = ready.getFileName().toString().replace(".trg", "");
        // Error files block later dates even when failure happened before job metadata existed.
        if (Files.isDirectory(config.errorDirectory())) {
            try (var files = Files.list(config.errorDirectory())) {
                var prior = files.filter(p -> ERROR.matcher(p.getFileName().toString()).matches())
                    .filter(p -> p.getFileName().toString().substring(0,14).compareTo(stem) < 0).findFirst();
                if (prior.isPresent()) throw new IllegalStateException("Retry earlier failed trigger before later batches: " + prior.get());
            }
        }
        if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Ready trigger must be a regular file, not a symlink: " + ready);
        Path active = ready.resolveSibling(stem + ".INPROGRESS.trg");
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(active.toString());
        // Same-directory rename claims a fully published trigger before running any work.
        Files.move(ready, active, StandardCopyOption.ATOMIC_MOVE);
        String phase = "validate trigger and inputs";
        try {
            String compactDate = stem.substring(6);
            LocalDate date = LocalDate.parse(compactDate, DateTimeFormatter.BASIC_ISO_DATE);
            if (date.getYear() < 1) throw new IllegalArgumentException("Batch year must be positive");
            for (String type : new String[]{"customer", "account", "relationship", "transaction"}) {
                Path input = config.inputDirectory().resolve(type + "_" + compactDate + ".dat");
                if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Missing or non-regular input file: " + input);
            }
            phase = "batch job";
            log.info("Claimed trigger {} for batch {}", active, date);
            action.run(date);
            phase = "publish COMPLETE trigger";
            Path complete = config.outputDirectory().resolve(stem + ".COMPLETE.trg");
            if (Files.exists(complete, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(complete, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(active, complete) != -1)
                    throw new IOException("Conflicting COMPLETE trigger: " + complete);
            } else {
                publish(active, complete);
            }
            // Preserve previous diagnostics, but remove the failed-date barrier after success.
            archiveError(config.errorDirectory().resolve(stem + ".ERROR.trg"));
            Files.delete(active);
            log.info("Completed trigger {}", complete);
            return true;
        } catch (Exception failure) {
            Path error = config.errorDirectory().resolve(stem + ".ERROR.trg");
            try {
                var text = new StringWriter();
                try (var writer = new PrintWriter(text)) {
                    writer.println("Trigger: " + ready.getFileName());
                    writer.println("Failed at (UTC): " + Instant.now());
                    writer.println("Phase: " + phase);
                    failure.printStackTrace(writer);
                }
                Files.createDirectories(config.errorDirectory());
                archiveError(error);
                Path temporary = Files.createTempFile(config.errorDirectory(), ".trigger-error-", ".tmp");
                try {
                    Files.writeString(temporary, text.toString());
                    Files.move(temporary, error, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
                Files.delete(active);
                log.error("Batch trigger failed; diagnostics written to {}", error);
            } catch (Exception finalizationFailure) {
                failure.addSuppressed(finalizationFailure);
                log.error("Unable to finalize trigger {}. INPROGRESS may remain; operator investigation required", active, finalizationFailure);
            }
            throw failure; // Stop this monitor: later dates must not overtake a failed batch.
        }
    }

    private static void publish(Path source, Path target) throws IOException {
        // Input/output may be different container mounts. Publish a complete destination
        // file atomically, then remove the source only after successful publication.
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".trigger-complete-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void archiveError(Path error) throws IOException {
        if (!Files.exists(error, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(error, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular ERROR trigger: " + error);
        Path archive = error.getParent().resolve("archive");
        Files.createDirectories(archive);
        Files.move(error, archive.resolve(error.getFileName() + "." + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
    }
}
