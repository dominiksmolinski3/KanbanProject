package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ClientRoutesExistTest {
    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    private static final Path SERVICES = Path.of("..", "frontend", "src", "services");

    private static final Map<String, List<String>> CALLER_SUPPLIED_PATHS = Map.of(
            "{}/positions", List.of("/api/tasks/positions", "/api/columns/positions", "/api/rows/positions"));

    @Test
    @DisplayName("every path the client fetches is a path some controller answers on")
    void everyClientCallReachesARoute() throws IOException {
        Set<String> served = servedRoutes();
        Map<String, String> calls = clientCalls();
        Set<String> called = new TreeSet<>(calls.keySet());
        called.removeAll(CALLER_SUPPLIED_PATHS.keySet());

        assertThat(called)
                .as("these are read out of frontend/src/services and no @RestController maps them - "
                        + "either the route moved and the client did not, or the call is dead. "
                        + "Call sites: %s", calls)
                .isSubsetOf(served);
    }

    @Test
    @DisplayName("the calls whose path is built by the caller are the ones named here, and no others")
    void callerSuppliedPathsAreTheKnownOnes() throws IOException {
        Map<String, String> calls = clientCalls();
        Map<String, String> unresolved = new TreeMap<>();
        calls.forEach((call, site) -> {
            if (call.startsWith("{}")) {
                unresolved.put(call, site);
            }
        });

        assertThat(unresolved.keySet())
                .as("a new way of building a request URL at %s: resolve it here, or name it in "
                        + "CALLER_SUPPLIED_PATHS with the routes it stands for", unresolved)
                .isEqualTo(new TreeSet<>(CALLER_SUPPLIED_PATHS.keySet()));
    }

    @Test
    @DisplayName("the routes behind those calls exist too")
    void callerSuppliedPathsResolveToRealRoutes() {
        Set<String> served = servedRoutes();

        CALLER_SUPPLIED_PATHS.forEach((call, routes) -> assertThat(routes)
                .as("%s is documented as standing for these, and at least one is not served", call)
                .isSubsetOf(served));
    }

    @Test
    @DisplayName("the comparison is actually reading both sides")
    void bothSidesAreNonEmpty() throws IOException {
        assertThat(servedRoutes()).hasSizeGreaterThan(40);
        assertThat(clientCalls().keySet()).hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("no fetch call is quietly skipped on the way")
    void everyFetchCallIsAccountedFor() throws IOException {
        Map<String, List<String>> skipped = new TreeMap<>();
        for (Path file : serviceFiles()) {
            String source = Files.readString(file);
            String live = withoutComments(source);
            Map<String, String> names = namesIn(live);

            Matcher matcher = FETCH.matcher(live);
            while (matcher.find()) {
                String path = resolve(firstArgument(live, matcher.end()), names);
                if (!path.startsWith("/api") && !path.startsWith("{}")) {
                    skipped.computeIfAbsent(siteOf(file, source, matcher.start()), site -> List.of(path));
                }
            }
        }

        assertThat(skipped)
                .as("a live fetch whose path this test could not read at all - it is being ignored, "
                        + "not checked")
                .isEmpty();
    }

    private static Set<String> servedRoutes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> routes = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException cause) {
                throw new IllegalStateException("scanned a controller that will not load", cause);
            }
            if (type.getEnclosingClass() != null) {
                continue;
            }
            for (String base : pathsOf(AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class))) {
                for (Method method : type.getDeclaredMethods()) {
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping == null) {
                        continue;
                    }
                    for (String suffix : pathsOf(mapping)) {
                        routes.add(normalise("/api" + base + suffix));
                    }
                }
            }
        }
        return routes;
    }

    private static String[] pathsOf(RequestMapping mapping) {
        if (mapping == null || mapping.value().length == 0) {
            return new String[]{""};
        }
        return mapping.value();
    }

    private static final Pattern FETCH = Pattern.compile("\\bfetch\\s*\\(");
    private static final Pattern CONSTANT =
            Pattern.compile("const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(`[^`]*`|'[^']*'|\"[^\"]*\")\\s*;");
    private static final Pattern HELPER =
            Pattern.compile("const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\([^)]*\\)\\s*=>\\s*(`[^`]*`)\\s*;");
    private static final Pattern OBJECT_ENTRY =
            Pattern.compile("const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern OBJECT_FIELD =
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*:\\s*'([^']*)'");

    private static Map<String, String> clientCalls() throws IOException {
        Map<String, String> calls = new TreeMap<>();
        for (Path file : serviceFiles()) {
            String source = withoutComments(Files.readString(file));
            Map<String, String> names = namesIn(source);

            Matcher matcher = FETCH.matcher(source);
            while (matcher.find()) {
                String argument = firstArgument(source, matcher.end());
                String path = resolve(argument, names);
                if (path.startsWith("/api") || path.startsWith("{}")) {
                    calls.putIfAbsent(normalise(path), siteOf(file, source, matcher.start()));
                }
            }
        }
        return calls;
    }

    private static String withoutComments(String source) {
        StringBuilder stripped = new StringBuilder(source);
        char quote = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;

            if (inLineComment) {
                blank(stripped, index, index + 1);
                inLineComment = character != '\n';
            } else if (inBlockComment) {
                blank(stripped, index, index + 1);
                if (character == '*' && next == '/') {
                    blank(stripped, index + 1, index + 2);
                    index++;
                    inBlockComment = false;
                }
            } else if (quote != 0) {
                if (character == '\\') {
                    index++;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '/' && next == '/') {
                blank(stripped, index, index + 2);
                index++;
                inLineComment = true;
            } else if (character == '/' && next == '*') {
                blank(stripped, index, index + 2);
                index++;
                inBlockComment = true;
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            }
        }
        return stripped.toString();
    }

    private static void blank(StringBuilder text, int from, int to) {
        for (int index = from; index < to; index++) {
            if (text.charAt(index) != '\n') {
                text.setCharAt(index, ' ');
            }
        }
    }

    private static String siteOf(Path file, String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset && index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return file.getFileName() + ":" + line;
    }

    private static List<Path> serviceFiles() throws IOException {
        assertThat(SERVICES).as("the client's service layer has moved or gone").isDirectory();
        try (Stream<Path> files = Files.list(SERVICES)) {
            return files.filter(file -> file.toString().endsWith(".js")).sorted().toList();
        }
    }

    private static Map<String, String> namesIn(String source) {
        Map<String, String> names = new LinkedHashMap<>();

        Matcher constants = CONSTANT.matcher(source);
        while (constants.find()) {
            names.put(constants.group(1), unquote(constants.group(2)));
        }
        Matcher helpers = HELPER.matcher(source);
        while (helpers.find()) {
            names.put(helpers.group(1) + "()", unquote(helpers.group(2)));
        }
        Matcher objects = OBJECT_ENTRY.matcher(source);
        while (objects.find()) {
            Matcher fields = OBJECT_FIELD.matcher(objects.group(2));
            while (fields.find()) {
                names.put(objects.group(1) + "." + fields.group(1), fields.group(2));
            }
        }
        return names;
    }

    private static String firstArgument(String source, int from) {
        int depth = 0;
        StringBuilder argument = new StringBuilder();
        for (int index = from; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '(' || character == '{' || character == '[') {
                depth++;
            } else if (character == ')' || character == '}' || character == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (character == ',' && depth == 0) {
                break;
            }
            argument.append(character);
        }
        return argument.toString().trim();
    }

    private static String resolve(String expression, Map<String, String> names) {
        String text = expression.trim();

        if (text.startsWith("onActiveBoard(")) {
            return resolve(unwrap(text, "onActiveBoard("), names);
        }
        if (names.containsKey(text)) {
            return interpolate(names.get(text), names);
        }
        Matcher call = Pattern.compile("^([A-Za-z_$][\\w$]*)\\s*\\(").matcher(text);
        if (call.find() && names.containsKey(call.group(1) + "()")) {
            return interpolate(names.get(call.group(1) + "()"), names);
        }
        if (text.startsWith("`") || text.startsWith("'") || text.startsWith("\"")) {
            return interpolate(unquote(text), names);
        }
        return "{}";
    }

    private static String interpolate(String template, Map<String, String> names) {
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < template.length(); index++) {
            if (template.startsWith("${", index)) {
                int end = closingBrace(template, index + 1);
                if (end < 0) {
                    return path.append("{}").toString();
                }
                String inner = template.substring(index + 2, end).trim();
                String resolved = names.containsKey(inner) || inner.matches("[A-Za-z_$][\\w$]*\\s*\\(.*")
                        ? resolve(inner, names)
                        : "{}";
                path.append(resolved);
                index = end;
            } else {
                path.append(template.charAt(index));
            }
        }
        return path.toString();
    }

    private static int closingBrace(String text, int openIndex) {
        int depth = 0;
        for (int index = openIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String unwrap(String text, String prefix) {
        String inner = text.substring(prefix.length()).trim();
        return inner.endsWith(")") ? inner.substring(0, inner.length() - 1).trim() : inner;
    }

    private static String unquote(String literal) {
        return literal.length() >= 2 ? literal.substring(1, literal.length() - 1) : literal;
    }

    private static String normalise(String path) {
        String normalised = path.replaceAll("\\{[^}]*}", "{}").replaceAll("/{2,}", "/");
        int query = normalised.indexOf('?');
        if (query >= 0) {
            normalised = normalised.substring(0, query);
        }
        if (normalised.length() > 1 && normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return normalised;
    }
}
