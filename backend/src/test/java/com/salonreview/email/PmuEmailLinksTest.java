package com.salonreview.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Reminder and follow-up emails must open the booking popup on the main PMU page, never on /book
 * or the retired book.pmu-annakara.com page. */
class PmuEmailLinksTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/email-templates/business-2");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @Test
    void templateLinksNeverPointAtBookPageOrRetiredSubdomain() throws IOException {
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                Matcher m = HREF.matcher(Files.readString(f));
                while (m.find()) {
                    String url = m.group(1);
                    assertThat(url).as("%s link %s", f.getFileName(), url)
                            .doesNotContain("book.pmu-annakara.com")
                            .doesNotContain("pmu-annakara.com/book");
                }
            }
        }
    }

    @Test
    void reminderTemplatesTellWhichDeepLinkTheyUse() throws IOException {
        assertThat(Files.readString(TEMPLATES.resolve("touchup_reminder.html")))
                .contains("https://pmu-annakara.com/?book=touch-up");
        assertThat(Files.readString(TEMPLATES.resolve("color_booster_reminder.html")))
                .contains("https://pmu-annakara.com/?book=color-booster");
    }

    @Test
    void javaBookingLinksUseMainPage() throws IOException {
        List<Path> sources = List.of(
                Path.of("src/main/java/com/salonreview/sms/ColorBoosterWinbackOneOffService.java"),
                Path.of("src/main/java/com/salonreview/sms/LifecycleReminderEmailFallbackScheduler.java"));
        for (Path p : sources) {
            assertThat(Files.readString(p)).as(p.toString())
                    .doesNotContain("book.pmu-annakara.com/?book")
                    .contains("https://pmu-annakara.com/?book=");
        }
    }
}
