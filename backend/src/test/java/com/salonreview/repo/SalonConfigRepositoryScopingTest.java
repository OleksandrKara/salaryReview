package com.salonreview.repo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.7's forcing function ({@code SalonConfigRepository} extends the bare {@code Repository}
 * marker, not {@code JpaRepository}) already makes a reintroduced {@code findById(1)} call site fail
 * to compile. This test guards the forcing function itself: it fails loudly, at test time rather than
 * relying on a future contributor noticing a compile error, if either (a) {@code salonConfig
 * .findById(} ever reappears in the source tree, or (b) the interface is widened back to something
 * that would make {@code findById} compile again.
 */
class SalonConfigRepositoryScopingTest {

    @Test
    void repositoryIsNotAJpaRepositoryOrCrudRepository() {
        assertThat(org.springframework.data.jpa.repository.JpaRepository.class
                .isAssignableFrom(SalonConfigRepository.class)).isFalse();
        assertThat(org.springframework.data.repository.CrudRepository.class
                .isAssignableFrom(SalonConfigRepository.class)).isFalse();
    }

    @Test
    void noSourceFileCallsSalonConfigFindById() throws IOException {
        Path mainSrc = Paths.get("src/main/java");
        assertThat(Files.isDirectory(mainSrc))
                .as("expected to run with the backend module directory as the working directory")
                .isTrue();

        Pattern offendingCall = Pattern.compile("salonConfig\\s*\\.\\s*findById\\s*\\(");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(mainSrc)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return offendingCall.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders)
                .as("salon_config must be looked up via findByBusinessId(currentBusinessContext.id()),"
                        + " not the removed findById(1)")
                .isEmpty();
    }
}
