package pl.myproject.kanbanproject2.config;

import jakarta.persistence.Entity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The schema Hibernate would create from the entities, without needing a database to ask.
 *
 * <p>This is the other half of moving to {@code ddl-auto=validate}: the baseline migration has to
 * say exactly what the entities say, and the only authority on that is Hibernate's own mapping
 * metadata. Reading it here rather than dumping a live database also means the answer does not
 * depend on which environment happened to be running when someone looked.
 */
public final class SchemaDdl {

    private static final String ENTITY_PACKAGE = "pl.myproject.kanbanproject2";

    private SchemaDdl() {
    }

    /** Every {@code @Entity} class on the classpath, found the same way Spring Boot finds them. */
    public static List<Class<?>> entityClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        var classes = new java.util.ArrayList<Class<?>>();
        scanner.findCandidateComponents(ENTITY_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .sorted()
                .forEach(name -> {
                    try {
                        classes.add(Class.forName(name));
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("scanned but could not load " + name, e);
                    }
                });
        return classes;
    }

    /**
     * The CREATE statements for the PostgreSQL dialect.
     *
     * <p>Driven through the JPA-standard {@code schema-generation.scripts} settings rather than
     * {@code SchemaExport}, which Hibernate 6.6 no longer ships in core.
     */
    public static String create() {
        Path target;
        try {
            target = Files.createTempFile("kanban-schema", ".sql");
        } catch (IOException e) {
            throw new IllegalStateException("could not allocate a file for the generated DDL", e);
        }

        var settings = new java.util.HashMap<String, Object>();
        settings.put(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        // There is no database to ask, and asking is what logs a stack trace on the way past.
        settings.put(AvailableSettings.ALLOW_METADATA_ON_BOOT, "false");
        settings.put(AvailableSettings.FORMAT_SQL, "false");
        settings.put(AvailableSettings.HBM2DDL_CHARSET_NAME, "UTF-8");
        settings.put(AvailableSettings.HBM2DDL_DELIMITER, ";");
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION, "create");
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_CREATE_TARGET, target.toString());
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_CREATE_SCHEMAS, "false");
        // Spring Boot's defaults, not Hibernate's. Without these the generated DDL says
        // `recipientId` and `wipLimit` where the running application says `recipient_id` and
        // `wip_limit`, and a baseline written from it fails ddl-auto=validate on the first start.
        settings.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY,
                "org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
        settings.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");

        var builder = new StandardServiceRegistryBuilder();
        settings.forEach(builder::applySetting);
        var registry = builder.build();

        try {
            var sources = new MetadataSources(registry);
            entityClasses().forEach(sources::addAnnotatedClass);
            var metadata = sources.buildMetadata();

            SchemaManagementToolCoordinator.process(metadata, registry, settings, action -> {
                // nothing is created against a live database here, so there is nothing to drop
            });

            return Files.readString(target);
        } catch (IOException e) {
            throw new IllegalStateException("could not read the generated DDL back", e);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // a temp file the OS will collect
            }
        }
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("create table\\s+(\\S+?)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** Lower-cased table names appearing in a DDL script, from either Hibernate or a migration. */
    public static Set<String> tableNames(String ddl) {
        var names = new TreeSet<String>();
        Matcher matcher = CREATE_TABLE.matcher(ddl);
        while (matcher.find()) {
            names.add(matcher.group(1).replace("\"", "").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** The {@code table.column} pairs a CREATE TABLE script declares, lower-cased. */
    public static Set<String> columnNames(String ddl) {
        var columns = new TreeSet<String>();
        Matcher matcher = CREATE_TABLE.matcher(ddl);
        while (matcher.find()) {
            String table = matcher.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
            int open = matcher.end() - 1;
            int close = matchingParen(ddl, open);
            for (String part : splitTopLevel(ddl.substring(open + 1, close))) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String first = trimmed.split("\\s+")[0].replace("\"", "").toLowerCase(Locale.ROOT);
                // constraint clauses are not columns
                if (Set.of("primary", "foreign", "unique", "constraint", "check").contains(first)) {
                    continue;
                }
                columns.add(table + "." + first);
            }
        }
        return columns;
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("unbalanced parentheses in DDL at offset " + open);
    }

    private static List<String> splitTopLevel(String body) {
        var parts = new java.util.ArrayList<String>();
        int depth = 0;
        var current = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
