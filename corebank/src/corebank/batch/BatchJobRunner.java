package corebank.batch;

import corebank.BatchLocks;
import corebank.Config;
import corebank.ingest.BatchIngestor;
import corebank.processing.BatchProcessor;
import corebank.output.BalanceExporter;
import java.sql.Connection;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Durable three-step orchestration. Business operations retain their own transactions. */
public final class BatchJobRunner {
    private static final Logger log = LoggerFactory.getLogger(BatchJobRunner.class);
    private static final String JOB_NAME = "dailyBankingJob";

    public void run(Config config, LocalDate date) throws Exception {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999)
            throw new IllegalArgumentException("Batch date must have a four-digit positive year");
        var db = config.database();
        var source = new DriverManagerDataSource(db.url(), db.username(), db.password());
        // Dedicated session spans job metadata and all business commits. Fail fast rather
        // than queue an out-of-order batch behind an unknown active process.
        try (var guard = source.getConnection()) {
            try (var s = guard.prepareStatement("SELECT pg_try_advisory_lock(?,0)")) {
                s.setInt(1, BatchLocks.JOB_LOCK_NAMESPACE);
                try (var rs = s.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) throw new IllegalStateException("Another batch job is active; retry after it finishes");
                }
            }
            checkPriorJobs(guard, date);
            var factory = new JobRepositoryFactoryBean();
            factory.setDataSource(source);
            factory.setTransactionManager(new DataSourceTransactionManager(source));
            factory.setTablePrefix("core_batch.BATCH_");
            factory.afterPropertiesSet();
            var repository = factory.getObject();
            // These tasklets must not wrap the business connections in a new transaction:
            // each existing service owns its connection, commits, and recovery protocol.
            var stepTransactions = new ResourcelessTransactionManager();
            var ingest = new StepBuilder("ingest", repository).tasklet((contribution, context) -> {
                try (var c = source.getConnection()) {
                    String status = businessStatus(c, date);
                    if ("RUNNING".equals(status))
                        throw new IllegalStateException("Unfinished core RUNNING attempt requires operator recovery: " + date);
                    // A business commit may have succeeded before step metadata was saved.
                    if (!"COMPLETED".equals(status)) {
                        var counts = new BatchIngestor().ingest(c, config.inputDirectory(), date);
                        log.info("Staged batch {}: {}", date, counts);
                    } else {
                        log.info("Skipped ingestion for batch {}: posting already completed", date);
                    }
                }
                return RepeatStatus.FINISHED;
            }, stepTransactions).allowStartIfComplete(true).build();
            var process = new StepBuilder("process", repository).tasklet((contribution, context) -> {
                try (var c = source.getConnection()) {
                    var counts = new BatchProcessor().process(c, date);
                    log.info("Completed batch {} (stored applied counts; completed retries are no-ops): {}", date, counts);
                }
                return RepeatStatus.FINISHED;
            }, stepTransactions).build();
            var output = new StepBuilder("output", repository).tasklet((contribution, context) -> {
                try (var c = source.getConnection()) {
                    var file = new BalanceExporter().export(c, config.outputDirectory(), date);
                    log.info("Published balance output for {}: {}", date, file);
                }
                return RepeatStatus.FINISHED;
            }, stepTransactions).build();
            var job = new JobBuilder(JOB_NAME, repository).start(ingest).next(process).next(output).build();
            var launcher = new TaskExecutorJobLauncher();
            launcher.setJobRepository(repository);
            launcher.afterPropertiesSet(); // synchronous execution
            try {
                var execution = launcher.run(job, new JobParametersBuilder()
                    .addString("batchDate", date.toString(), true).toJobParameters());
                if (execution.getStatus() != BatchStatus.COMPLETED)
                    throw new IllegalStateException("Batch job " + date + " ended " + execution.getStatus()
                        + "; inspect core_batch execution history and correct the cause before retrying",
                        execution.getAllFailureExceptions().stream().findFirst().orElse(null));
            } catch (JobInstanceAlreadyCompleteException completed) {
                // Same business date identifies the same job, never a fresh timestamped run.
                log.info("Batch job already completed: {}; use --output to regenerate a file", date);
            }
        } // Closing the dedicated guard connection releases its session advisory lock.
    }

    private static String businessStatus(Connection c, LocalDate date) throws Exception {
        try (var s = c.prepareStatement("SELECT status FROM core.batch_run WHERE batch_date=? AND status IN ('RUNNING','COMPLETED')")) {
            s.setObject(1, date);
            try (var rs = s.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static void checkPriorJobs(Connection c, LocalDate date) throws Exception {
        String sql = "SELECT p.parameter_value,e.status FROM core_batch.batch_job_execution e "
            + "JOIN core_batch.batch_job_instance i USING(job_instance_id) "
            + "JOIN core_batch.batch_job_execution_params p USING(job_execution_id) "
            + "WHERE i.job_name=? AND p.parameter_name='batchDate' "
            + "AND e.job_execution_id=(SELECT max(x.job_execution_id) FROM core_batch.batch_job_execution x WHERE x.job_instance_id=i.job_instance_id) "
            + "AND (e.status IN ('STARTING','STARTED','STOPPING','UNKNOWN') OR (p.parameter_value<? AND e.status<>'COMPLETED')) LIMIT 1";
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, JOB_NAME); s.setString(2, date.toString());
            try (var rs = s.executeQuery()) {
                if (rs.next()) throw new IllegalStateException("Resolve batch job " + rs.getString(1)
                    + " (" + rs.getString(2) + ") before launching " + date
                    + "; active/abandoned jobs require investigation, earlier failed jobs must be retried");
            }
        }
    }
}
