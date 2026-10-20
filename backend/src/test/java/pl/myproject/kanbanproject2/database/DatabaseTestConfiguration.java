package pl.myproject.kanbanproject2.database;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "pl.myproject.kanbanproject2")
public class DatabaseTestConfiguration {

    public static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    private static final String HOW_TO_RUN = "These tests run against a real Postgres. Point TEST_DATABASE_URL, "
            + "TEST_DATABASE_USERNAME and TEST_DATABASE_PASSWORD at one (CI's backend/.env is read as a fallback), "
            + "for example: docker run -d --name kanban-verify-postgres -p 15433:5432 -e POSTGRES_DB=kanban_ci "
            + "-e POSTGRES_USER=kanban_ci -e POSTGRES_PASSWORD=kanban_ci postgres:17 and "
            + "TEST_DATABASE_URL=jdbc:postgresql://localhost:15433/kanban_ci. Each run migrates a schema of its own "
            + "and drops it afterwards.";

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        Map<String, String> dotEnv = readDotEnv(Path.of(".env"));
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(setting("TEST_DATABASE_URL", "SPRING_DATASOURCE_URL", dotEnv));
        dataSource.setUsername(setting("TEST_DATABASE_USERNAME", "SPRING_DATASOURCE_USERNAME", dotEnv));
        dataSource.setPassword(setting("TEST_DATABASE_PASSWORD", "SPRING_DATASOURCE_PASSWORD", dotEnv));
        dataSource.setSchema(SCHEMA);
        dataSource.setMaximumPoolSize(6);
        try (var ignored = dataSource.getConnection()) {
            return dataSource;
        } catch (Exception e) {
            dataSource.close();
            throw new IllegalStateException(HOW_TO_RUN, e);
        }
    }

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    @DependsOn("flyway")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("pl.myproject.kanbanproject2");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var properties = new Properties();
        properties.put("hibernate.hbm2ddl.auto", "validate");
        properties.put("hibernate.default_schema", SCHEMA);
        properties.put("hibernate.order_updates", "true");
        properties.put("hibernate.implicit_naming_strategy", "org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
        properties.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        factory.setJpaProperties(properties);
        return factory;
    }

    @Bean
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DisposableBean dropSchemaOnClose(JdbcTemplate jdbc) {
        return () -> jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private static String setting(String name, String fallback, Map<String, String> dotEnv) {
        return Stream.of(System.getenv(name), System.getenv(fallback), dotEnv.get(fallback))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(name + " is not set. " + HOW_TO_RUN));
    }

    private static Map<String, String> readDotEnv(Path file) {
        var values = new HashMap<String, String>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0 && !line.stripLeading().startsWith("#")) {
                    values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file.toAbsolutePath(), e);
        }
        return values;
    }
}
