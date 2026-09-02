package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules a language tag has to survive between a browser saying it and a column holding it.
 *
 * <p>Two of them are worth stating rather than reading off the code. A tag arrives in whatever
 * shape its sender felt like - {@code pl}, {@code pl-PL}, {@code PL}, or {@code pl_PL} through
 * anything that has been round-tripped by a Java {@code Locale} - and all four name Polish. And
 * only the language survives: nine bundles with no regional variants between them means
 * {@code de-AT} and {@code de-DE} select the same messages, so a column keeping the distinction
 * would be a column inviting somebody to believe it was honoured.
 */
class SupportedLocalesTest {

    @Nested
    @DisplayName("reading a tag")
    class Reading {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"pl", "PL", "pl-PL", "pl_PL", "  pl-pl  ", "pl-Latn-PL"})
        @DisplayName("every shape a browser sends Polish in is Polish")
        void everyShapeOfOneLanguage(String tag) {
            assertThat(SupportedLocales.isSupported(tag)).isTrue();
            assertThat(SupportedLocales.normalise(tag)).isEqualTo("pl");
            assertThat(SupportedLocales.toLocale(tag)).isEqualTo(Locale.forLanguageTag("pl"));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"is", "zz", "klingon", "-", "-GB", "--"})
        @DisplayName("a tag naming no language we have messages for is not supported")
        void unknownTagsAreRejected(String tag) {
            assertThat(SupportedLocales.isSupported(tag)).isFalse();
        }

        @Test
        @DisplayName("only the primary subtag decides, however much is hung off it")
        void onlyThePrimarySubtagDecides() {
            assertThat(SupportedLocales.normalise("en-GB-oxendict")).isEqualTo("en");
        }

        @Test
        @DisplayName("nothing at all is not supported, and is not a crash either")
        void nothingIsNotSupported() {
            assertThat(SupportedLocales.isSupported(null)).isFalse();
            assertThat(SupportedLocales.isSupported("   ")).isFalse();
            assertThat(SupportedLocales.normalise(null)).isEqualTo("en");
        }

        @Test
        @DisplayName("normalise falls back rather than refusing, because its callers are guessing")
        void normaliseFallsBack() {
            assertThat(SupportedLocales.normalise("is-IS")).isEqualTo(SupportedLocales.DEFAULT);
        }
    }

    @Nested
    @DisplayName("reading an Accept-Language header")
    class AcceptLanguage {

        @Test
        @DisplayName("the first entry naming a language we have wins")
        void firstSupportedEntryWins() {
            assertThat(SupportedLocales.fromAcceptLanguage("is-IS,de-DE;q=0.9,en;q=0.8"))
                    .isEqualTo("de");
        }

        @Test
        @DisplayName("quality values are stripped rather than ordered by")
        void qualityValuesAreStripped() {
            assertThat(SupportedLocales.fromAcceptLanguage("fr-CH;q=0.7")).isEqualTo("fr");
        }

        @Test
        @DisplayName("a header naming nothing we have answers null, so the caller can decide")
        void nothingSupportedIsNull() {
            assertThat(SupportedLocales.fromAcceptLanguage("is-IS,zz")).isNull();
            assertThat(SupportedLocales.fromAcceptLanguage("")).isNull();
            assertThat(SupportedLocales.fromAcceptLanguage(null)).isNull();
        }

        @Test
        @DisplayName("a wildcard is not a language")
        void aWildcardIsNotALanguage() {
            assertThat(SupportedLocales.fromAcceptLanguage("*")).isNull();
        }
    }

    @Test
    @DisplayName("English is the default, because it is what V11 backfilled and what the base bundle is")
    void englishIsTheDefault() {
        assertThat(SupportedLocales.DEFAULT).isEqualTo("en");
        assertThat(SupportedLocales.TAGS).contains(SupportedLocales.DEFAULT);
    }
}
