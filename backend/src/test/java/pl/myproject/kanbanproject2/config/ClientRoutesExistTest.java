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

/**
 * Every URL the client asks for against every URL the server answers on, compared at build time.
 *
 * <p>This is the coupling an OpenAPI document exists to make visible, and it is the one the two
 * test suites are each blind to from their own side. Jest stubs {@code fetch}, so a frontend test
 * asserts that a request was made to a string and never that anything serves it; the backend suite
 * asserts routes that no client necessarily calls. Between them a route can be renamed, moved or
 * removed and both suites stay green while the feature is dead in the browser - which is not
 * hypothetical here: {@code POST /api/boards/{id}/members} was deliberately removed when
 * invitations landed, and finding the client calls that went with it was a manual read.
 *
 * <p>What it does <em>not</em> check is the other direction. A backend route nothing calls is not a
 * defect - {@code FileController} has been owned and unused for several revisions on purpose - so
 * this is a subset assertion, not an equality.
 *
 * <p>Same shape as {@link ConfigurationTest}, {@code DeadLetterAlertTest} and
 * {@code SupportedLocalesMatchClientTest}: a rule spanning two trees, checked in one, needing no
 * database, no bundle and no running container. It does not skip when the files are missing,
 * because a guard that turns itself off leaves the build green either way.
 */
class ClientRoutesExistTest {

    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path SERVICES = Path.of("..", "frontend", "src", "services");

    /**
     * The calls whose path is decided by the caller, with the routes each one stands for.
     *
     * <p>Named rather than skipped. {@code reorder(endpoint, ...)} takes the container as an
     * argument precisely so that one function serves three routes, which is the right shape and is
     * also the one shape this file cannot resolve by reading. Listing them here means a
     * <em>fourth</em> unresolvable call - a genuinely new way of building a URL - fails the build
     * and has to be looked at, rather than quietly joining a set nothing asserts about.
     */
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
            // A path whose first segment is a placeholder was assembled from something this file
            // could not follow - a parameter, an import, a value off a response.
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
        // Either half silently reading nothing makes a subset assertion pass for the wrong reason,
        // which is the failure mode of every test that compares two collections.
        assertThat(servedRoutes()).hasSizeGreaterThan(40);
        assertThat(clientCalls().keySet()).hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("no fetch call is quietly skipped on the way")
    void everyFetchCallIsAccountedFor() throws IOException {
        // The assertion that stops this test from reading less than it claims to. Every fetch in
        // the source is either inside a comment - the stripper blanked it, and there is one, in
        // the prose above downloadTaskAttachment - or it produced a path. Anything else means the
        // scanner walked past a call, which looks exactly like a client with fewer calls.
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

    // ---------------------------------------------------------------- the server side

    /** Every mapped path, with {@code /api} applied as {@code WebConfig} applies it. */
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
            // A nested controller is a probe defined inside a test - ApiPathPrefixTest has two -
            // and the scan cannot tell test-classes from classes. Leaving them in would let a
            // throwaway @RequestMapping in a test satisfy a client call for a route that has gone.
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

    /** The declared paths, or a single empty one - a mapping with no path still maps its parent. */
    private static String[] pathsOf(RequestMapping mapping) {
        if (mapping == null || mapping.value().length == 0) {
            return new String[]{""};
        }
        return mapping.value();
    }

    // ---------------------------------------------------------------- the client side

    private static final Pattern FETCH = Pattern.compile("\\bfetch\\s*\\(");
    /** {@code const NAME = '...'} or a backtick template, on one line. */
    private static final Pattern CONSTANT =
            Pattern.compile("const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(`[^`]*`|'[^']*'|\"[^\"]*\")\\s*;");
    /** {@code const NAME = (args) => `...`} - a one-line path helper. */
    private static final Pattern HELPER =
            Pattern.compile("const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\([^)]*\\)\\s*=>\\s*(`[^`]*`)\\s*;");
    /** {@code KEY: '/api/...'} inside an endpoint object. */
    private static final Pattern OBJECT_ENTRY =
            Pattern.compile("const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern OBJECT_FIELD =
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*:\\s*'([^']*)'");

    /**
     * Every path {@code fetch} is called with, against the first place it is called from.
     *
     * <p>The call site is carried so a failure names a line rather than a normalised string. The
     * first draft reported the string alone, and the first thing it reported was a placeholder
     * that turned out to be a {@code fetch()} written inside a doc comment - which cost more to
     * find than the fix did.
     */
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

    /**
     * The source with its comments blanked out, keeping every offset where it was.
     *
     * <p>Blanked rather than removed so the line numbers a failure reports still point at the
     * file.
     *
     * <p>Scanned character by character rather than matched with a pattern, and the reason is a
     * bug this test had on its first run. {@code /\*.*?\*​/} finds a comment start inside a string
     * literal: {@code 'Accept': 'image/*, application/json'} in {@code getUserAvatar} opened one,
     * the next real {@code *​/} a hundred lines below closed it, and four {@code fetch} calls in
     * between were blanked and never checked. The test stayed green while reading less than it
     * claimed to - which is the one failure mode a guard must not have, and is not visible from
     * its result.
     *
     * <p>The remaining limit, stated rather than hidden: a regular-expression literal containing a
     * quote would confuse this the same way. There is none in these files, and
     * {@code everyFetchCallIsAccountedFor} is what would notice.
     */
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

    /** Constants, one-line path helpers and endpoint-object fields, by the name a call would use. */
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

    /** The text of the first argument, balanced across nested calls and template literals. */
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

    /**
     * A request expression reduced to a path.
     *
     * <p>Wrappers that only add a query string are unwrapped, names are looked up, and anything
     * that is still an expression becomes {@code {}} - which is what a path variable is on the
     * server side too, so the two normalise onto the same string.
     */
    private static String resolve(String expression, Map<String, String> names) {
        String text = expression.trim();

        // onActiveBoard(x) appends ?boardId=, which the path does not carry. Unwrapping it leaves
        // the call's own closing paren behind, and a stray one turns unquote into an off-by-one
        // that keeps a backtick on the end of the path.
        if (text.startsWith("onActiveBoard(")) {
            return resolve(unwrap(text, "onActiveBoard("), names);
        }
        // A name resolves to its value, which is already raw text rather than a literal - so it
        // goes to interpolate, not back through here. Sending it back through here was the first
        // draft, and every path in the client came out as a bare placeholder, because
        // `/api/boards` is not a quoted string and fell off the end of this method.
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

    /** Replaces every {@code ${...}} with what it resolves to, or with a path placeholder. */
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

    /** The argument of a single-argument wrapper call, without the call's own closing paren. */
    private static String unwrap(String text, String prefix) {
        String inner = text.substring(prefix.length()).trim();
        return inner.endsWith(")") ? inner.substring(0, inner.length() - 1).trim() : inner;
    }

    private static String unquote(String literal) {
        return literal.length() >= 2 ? literal.substring(1, literal.length() - 1) : literal;
    }

    // ---------------------------------------------------------------- shared normalisation

    /** One spelling for both sides: no query string, no trailing slash, every variable as {@code {}}. */
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
