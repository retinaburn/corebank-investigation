package corebank;

import corebank.batch.TriggerMonitor;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TriggerMonitorTest {
    @TempDir Path root;
    Config config;
    @BeforeEach void setup() throws Exception {
        config=new Config(Files.createDirectory(root.resolve("input")),Files.createDirectory(root.resolve("output")),Files.createDirectory(root.resolve("error")),null);
    }
    Path input(String name) { return config.inputDirectory().resolve(name); }
    void ready(String date) throws Exception {
        for(String type:new String[]{"customer","account","relationship","transaction"}) Files.writeString(input(type+"_"+date+".dat"), "");
        Files.writeString(input("batch_"+date+".trg"), "");
    }
    @Test void claimsBeforeRunningAndCompletesInOutput() throws Exception {
        ready("20260101");
        var monitor=new TriggerMonitor(config,date->{
            assertEquals(LocalDate.of(2026,1,1),date);
            assertTrue(Files.exists(input("batch_20260101.INPROGRESS.trg")));
            assertFalse(Files.exists(input("batch_20260101.trg")));
        });
        assertTrue(monitor.processNext());
        assertFalse(Files.exists(input("batch_20260101.INPROGRESS.trg")));
        assertTrue(Files.exists(config.outputDirectory().resolve("batch_20260101.COMPLETE.trg")));
    }
    @Test void missingInputImmediatelyProducesErrorWithoutLaunchingJob() throws Exception {
        ready("20260101");Files.delete(input("account_20260101.dat"));
        var monitor=new TriggerMonitor(config,date->fail("Must not launch with missing input"));
        assertThrows(IOException.class,monitor::processNext);
        assertTrue(Files.readString(config.errorDirectory().resolve("batch_20260101.ERROR.trg")).contains("account_20260101.dat"));
        assertFalse(Files.exists(input("batch_20260101.INPROGRESS.trg")));
    }
    @Test void nestedAndSuppressedFailuresAreWrittenToError() throws Exception {
        ready("20260101");
        var monitor=new TriggerMonitor(config,date->{
            var failure=new IOException("output failed",new IOException("disk full"));
            failure.addSuppressed(new IOException("additional diagnostic"));throw failure;
        });
        assertThrows(IOException.class,monitor::processNext);
        String error=Files.readString(config.errorDirectory().resolve("batch_20260101.ERROR.trg"));
        assertTrue(error.contains("disk full"));assertTrue(error.contains("additional diagnostic"));
        assertFalse(Files.exists(input("batch_20260101.INPROGRESS.trg")));
    }
    @Test void startupBacklogProcessesInDateOrder() throws Exception {
        ready("20260103");ready("20260101");
        var dates=new ArrayList<LocalDate>();var monitor=new TriggerMonitor(config,dates::add);
        assertTrue(monitor.processNext());assertTrue(monitor.processNext());assertFalse(monitor.processNext());
        assertEquals(java.util.List.of(LocalDate.of(2026,1,1),LocalDate.of(2026,1,3)),dates);
    }
    @Test void failureBlocksLaterDateAcrossMonitorRestartAndRetryArchivesError() throws Exception {
        ready("20260101");ready("20260102");
        assertThrows(IOException.class,()->new TriggerMonitor(config,date->{throw new IOException("failed");}).processNext());
        var monitor=new TriggerMonitor(config,date->{});
        assertThrows(IllegalStateException.class,monitor::processNext);
        assertTrue(Files.exists(input("batch_20260102.trg")));
        ready("20260101");assertTrue(monitor.processNext());
        assertFalse(Files.exists(config.errorDirectory().resolve("batch_20260101.ERROR.trg")));
        try(var files=Files.list(config.errorDirectory().resolve("archive"))) { assertEquals(1,files.count()); }
        assertTrue(monitor.processNext());
    }
    @Test void abandonedInProgressBlocksNewWork() throws Exception {
        ready("20260102");Files.writeString(input("batch_20260101.INPROGRESS.trg"), "");
        assertThrows(IllegalStateException.class,()->new TriggerMonitor(config,date->fail()).processNext());
        assertTrue(Files.exists(input("batch_20260102.trg")));
    }
    @Test void ignoresTemporaryAndTerminalNames() throws Exception {
        Files.writeString(input("batch_20260101.trg.tmp"), "");
        Files.writeString(input("batch_20260101.COMPLETE.trg"), "");
        Files.writeString(input("batch_20260101.ERROR.trg"), "");
        assertFalse(new TriggerMonitor(config,date->fail()).processNext());
    }
    @Test void invalidCalendarDateBecomesError() throws Exception {
        ready("20260230");
        assertThrows(Exception.class,()->new TriggerMonitor(config,date->fail()).processNext());
        assertTrue(Files.exists(config.errorDirectory().resolve("batch_20260230.ERROR.trg")));
        assertFalse(Files.exists(input("batch_20260230.INPROGRESS.trg")));
    }
    @Test void completionPublicationFailureBecomesError() throws Exception {
        ready("20260101");Files.delete(config.outputDirectory());Files.writeString(config.outputDirectory(),"blocked");
        assertThrows(IOException.class,()->new TriggerMonitor(config,date->{}).processNext());
        assertTrue(Files.readString(config.errorDirectory().resolve("batch_20260101.ERROR.trg")).contains("publish COMPLETE"));
        assertFalse(Files.exists(input("batch_20260101.INPROGRESS.trg")));
    }
    @Test void duplicateIdenticalCompletionIsSafeAndConflictingCompletionIsPreserved() throws Exception {
        ready("20260101");var monitor=new TriggerMonitor(config,date->{});monitor.processNext();
        ready("20260101");monitor.processNext();
        ready("20260101");Files.writeString(input("batch_20260101.trg"),"different");
        assertThrows(IOException.class,monitor::processNext);
        assertEquals("",Files.readString(config.outputDirectory().resolve("batch_20260101.COMPLETE.trg")));
    }
    @Test void errorDirectoryFailureLeavesInProgressForInvestigation() throws Exception {
        ready("20260101");Files.delete(config.errorDirectory());Files.writeString(config.errorDirectory(),"blocked");
        assertThrows(IOException.class,()->new TriggerMonitor(config,date->{throw new IOException("job failed");}).processNext());
        assertTrue(Files.exists(input("batch_20260101.INPROGRESS.trg")));
    }
}
