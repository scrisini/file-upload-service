package com.crisinis.fileuploadservice;

import com.crisinis.fileuploadservice.logging.LogEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalogue is a contract with support: every code is unique, well formed and has an SOP. */
class LogEventCatalogueTests {

    private static final String PREFIX = Pattern.quote("FILE_UPLOAD_SERVICE");

    @Test
    void codesAreUniqueAndWellFormed() {
        assertThat(Arrays.stream(LogEvent.values()).map(LogEvent::code))
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches(PREFIX + "-[1-9]\\d{3}"));
    }

    @Test
    void everyEventHasASummaryAndErrorsSayWhatHappensNext() {
        for (LogEvent event : LogEvent.values()) {
            assertThat(event.summary()).as(event.code()).isNotBlank().doesNotEndWith(".");
        }
        assertThat(Arrays.stream(LogEvent.values()).filter(e -> e.level() == Level.ERROR))
                .allMatch(e -> e.summary().contains(";"));
    }

    @Test
    void everyCodeHasARunbookSectionAndEverySectionHasACode() throws IOException {
        String runbook = Files.readString(Path.of("docs/runbook.md"));
        Matcher sections = Pattern.compile("<a id=\"([^\"]+)\"></a>\\s*\\n### (" + PREFIX + "-\\d{4}):").matcher(runbook);
        Set<String> documented = new HashSet<>();
        while (sections.find()) {
            assertThat(sections.group(1)).as("anchor matches heading").isEqualTo(sections.group(2).toLowerCase());
            documented.add(sections.group(2));
        }
        Set<String> catalogued = Arrays.stream(LogEvent.values()).map(LogEvent::code).collect(Collectors.toSet());

        assertThat(documented).containsExactlyInAnyOrderElementsOf(catalogued);
    }
}
