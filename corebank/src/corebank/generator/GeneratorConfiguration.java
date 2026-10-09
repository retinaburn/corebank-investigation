package corebank.generator;

import corebank.Config;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Configuration binding only: deliberately no component scan or auto-configuration.
 * Loads Boot application YAML/profiles without core runners, JDBC beans or Liquibase.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Config.class)
public class GeneratorConfiguration {
    public static ConfigurableApplicationContext open() {
        return new SpringApplicationBuilder(GeneratorConfiguration.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .logStartupInfo(false)
            .run("--spring.liquibase.enabled=false");
    }
}
