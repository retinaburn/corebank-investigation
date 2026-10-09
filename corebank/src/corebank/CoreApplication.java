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
import corebank.processing.BatchProcessor;
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
            if (!arguments.containsOption("ingest") && !arguments.containsOption("process")) {
                log.info("Database migrations are up to date. Use --ingest=YYYY-MM-DD to stage files or --process=YYYY-MM-DD to post a staged batch.");
                return;
            }
            if (arguments.containsOption("ingest") && arguments.containsOption("process"))
                throw new IllegalArgumentException("Use --ingest and --process separately");
            String command = arguments.containsOption("ingest") ? "ingest" : "process";
            var values = arguments.getOptionValues(command);
            if (values == null || values.size() != 1 || !values.getFirst().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("Supply exactly one --" + command + "=YYYY-MM-DD");
            LocalDate date = LocalDate.parse(values.getFirst());
            var database = config.database();
            try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password())) {
                if (command.equals("ingest")) {
                    var counts = new BatchIngestor().ingest(connection, config.inputDirectory(), date);
                    log.info("Staged batch {}: {}", date, counts);
                } else {
                    var counts = new BatchProcessor().process(connection, date);
                    log.info("Completed batch {} (stored applied counts; completed retries are no-ops): {}", date, counts);
                }
            }
        };
    }
}
