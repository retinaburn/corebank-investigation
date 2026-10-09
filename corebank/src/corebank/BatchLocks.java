package corebank;

import java.time.LocalDate;

/** Shared PostgreSQL advisory-lock keys used by ingestion and processing. */
public final class BatchLocks {
    public static final int DATE_LOCK_NAMESPACE = 1129271877;

    private BatchLocks() {}

    public static int dateKey(LocalDate date) {
        return Math.toIntExact(date.toEpochDay());
    }
}
