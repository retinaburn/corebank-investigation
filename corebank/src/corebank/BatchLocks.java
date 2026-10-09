package corebank;

import java.time.LocalDate;

/** Shared PostgreSQL advisory-lock keys used by ingestion, processing, and output. */
public final class BatchLocks {
    public static final int DATE_LOCK_NAMESPACE = 1129271877;
    public static final int PROCESS_LOCK_NAMESPACE = 1129271878;
    public static final int OUTPUT_LOCK_NAMESPACE = 1129271879;

    public static final int JOB_LOCK_NAMESPACE = 1129271880;

    public static final int MONITOR_LOCK_NAMESPACE = 1129271881;

    private BatchLocks() {}

    public static int dateKey(LocalDate date) {
        return Math.toIntExact(date.toEpochDay());
    }
}
