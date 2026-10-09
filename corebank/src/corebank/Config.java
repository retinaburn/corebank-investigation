package corebank;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Core settings bound by Spring from application.yaml and external overrides. */
@ConfigurationProperties(prefix = "corebank")
public record Config(Path inputDirectory, Path outputDirectory, Path errorDirectory) {
}
