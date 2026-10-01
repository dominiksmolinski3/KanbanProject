package pl.myproject.kanbanproject2.config;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

import java.util.Arrays;

// Not a @Configuration: component scanning would otherwise pull it into the application's own context.
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public final class DatabaseMigration {
    public static final String FLAG = "--migrate-only";

    private DatabaseMigration() {
    }

    public static boolean requested(String[] args) {
        return Arrays.asList(args).contains(FLAG);
    }

    public static void run(String[] args) {
        new SpringApplicationBuilder(DatabaseMigration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties("spring.flyway.enabled=true")
                .run(args)
                .close();
    }
}
