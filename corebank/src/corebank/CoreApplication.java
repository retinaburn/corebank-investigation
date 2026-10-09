package corebank;

import java.sql.DriverManager;
import java.time.LocalDate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import corebank.ingest.BatchIngestor;
import corebank.batch.BatchJobRunner;
import corebank.batch.TriggerMonitor;
import corebank.processing.BatchProcessor;
import corebank.output.BalanceExporter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(Config.class)
public class CoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    @Bean
    CommandLineRunner startup(Config config, ApplicationArguments arguments) {
        return args -> {
            log.info("Data directories: input={}, output={}, error={}",
                config.inputDirectory(), config.outputDirectory(), config.errorDirectory());
            var commands = java.util.stream.Stream.of("ingest", "process", "output", "batch", "watch")
                .filter(arguments::containsOption).toList();
            if (commands.isEmpty()) {
                log.info("Database migrations are up to date. Use --ingest=YYYY-MM-DD, --process=YYYY-MM-DD, --output=YYYY-MM-DD, --batch=YYYY-MM-DD, or --watch.");
                return;
            }
            if (commands.size() != 1)
                throw new IllegalArgumentException("Use exactly one of --ingest, --process, --output, --batch, or --watch");
            String command = commands.getFirst();
            var values = arguments.getOptionValues(command);
            if (command.equals("watch")) {
                if (values != null && !values.isEmpty()) throw new IllegalArgumentException("Use --watch without a value");
                new TriggerMonitor(config).run();
                return;
            }
            if (values == null || values.size() != 1 || !values.getFirst().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("Supply exactly one --" + command + "=YYYY-MM-DD");
            LocalDate date = LocalDate.parse(values.getFirst());
            if (command.equals("batch")) {
                new BatchJobRunner().run(config, date);
                log.info("Batch job finished: {}", date);
                return;
            }
            var database = config.database();
            try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password())) {
                if (command.equals("ingest")) {
                    var counts = new BatchIngestor().ingest(connection, config.inputDirectory(), date);
                    log.info("Staged batch {}: {}", date, counts);
                } else if (command.equals("process")) {
                    var counts = new BatchProcessor().process(connection, date);
                    log.info("Completed batch {} (stored applied counts; completed retries are no-ops): {}", date, counts);
                } else {
                    var file = new BalanceExporter().export(connection, config.outputDirectory(), date);
                    log.info("Published balance output for {}: {}", date, file);
                }
            }
        };
    }
}
