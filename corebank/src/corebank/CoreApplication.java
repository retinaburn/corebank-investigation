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
            if (!arguments.containsOption("ingest")) {
                log.info("Database migrations are up to date. Use --ingest=YYYY-MM-DD to stage all four input files for a business date.");
                return;
            }
            var values = arguments.getOptionValues("ingest");
            if (values == null || values.size() != 1 || !values.getFirst().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("Supply exactly one --ingest=YYYY-MM-DD");
            LocalDate date = LocalDate.parse(values.getFirst());
            var database = config.database();
            try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password())) {
                var counts = new BatchIngestor().ingest(connection, config.inputDirectory(), date);
                log.info("Staged batch {}: {}", date, counts);
            }
        };
    }
}
