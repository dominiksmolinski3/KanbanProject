package pl.myproject.kanbanproject2.config;

import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every hand-written {@code @Query} in the repositories, compiled against the mapping metadata.
 *
 * <p>Derived queries are covered by {@code BoardDataIntegrityTest} through {@code PartTree}, which
 * is the parser Spring Data itself uses. A {@code @Query} is checked by nothing comparable: its
 * string is opaque until a JPA context boots, and nothing in this suite boots one - so a renamed
 * field or a typo inside one of them surfaced as a failure to start, which is the worst place to
 * find it. Hibernate will compile HQL without a database as long as it is told not to ask one for
 * metadata, so this asks it to, and fails the build instead.
 *
 * <p>It checks that a query resolves, not that it returns the right rows. What a query means is
 * still something only a database can answer - the null-cell branches in
 * {@code TaskRepository.findMaxPosition} are there because a real one was asked.
 */
class QueryStringsResolveTest {

    private record RepositoryQuery(String owner, String method, String hql) {

        /**
         * {@code createQuery} is for selects only and rejects an {@code UPDATE} or {@code DELETE}
         * outright, so a {@code @Modifying} query would have gone unchecked - or worse, been read
         * as a broken query. Hibernate compiles those through {@code createMutationQuery}, which
         * is the same parser and the same failure for a typo.
         */
        boolean isMutation() {
            String start = hql.stripLeading().toUpperCase(java.util.Locale.ROOT);
            return start.startsWith("UPDATE") || start.startsWith("DELETE") || start.startsWith("INSERT");
        }
    }

    @Test
    @DisplayName("every @Query in the repositories resolves against the entity mappings")
    void everyQueryResolves() {
        var queries = declaredQueries();
        assertThat(queries)
                .as("the repositories should still carry hand-written queries for this to guard")
                .isNotEmpty();
        assertThat(queries)
                .as("both kinds have to be reachable, or the mutation branch below guards nothing")
                .anyMatch(RepositoryQuery::isMutation)
                .anyMatch(query -> !query.isMutation());

        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                // There is no database here, and asking for one is what logs a stack trace.
                .applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, "false")
                .applySetting(AvailableSettings.IMPLICIT_NAMING_STRATEGY,
                        "org.springframework.boot.hibernate.SpringImplicitNamingStrategy")
                .applySetting(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();

        try {
            var sources = new MetadataSources(registry);
            SchemaDdl.entityClasses().forEach(sources::addAnnotatedClass);

            try (var factory = sources.buildMetadata().buildSessionFactory();
                 var session = factory.openSession()) {
                for (RepositoryQuery query : queries) {
                    try {
                        if (query.isMutation()) {
                            session.createMutationQuery(query.hql());
                        } else {
                            session.createQuery(query.hql(), Object.class);
                        }
                    } catch (RuntimeException e) {
                        throw new AssertionError(
                                query.owner() + "." + query.method() + " does not resolve: "
                                        + e.getMessage(), e);
                    }
                }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** The non-native {@code @Query} strings on every {@code @Repository} interface. */
    private static List<RepositoryQuery> declaredQueries() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Repository.class));

        var found = new ArrayList<RepositoryQuery>();
        for (var candidate : scanner.findCandidateComponents("pl.myproject.kanbanproject2")) {
            Class<?> repository;
            try {
                repository = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned but could not load " + candidate, e);
            }
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null && !query.nativeQuery() && !query.value().isBlank()) {
                    found.add(new RepositoryQuery(
                            repository.getSimpleName(), method.getName(), query.value()));
                }
            }
        }
        return found;
    }
}
