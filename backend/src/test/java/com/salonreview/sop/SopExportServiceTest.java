package com.salonreview.sop;

import com.salonreview.domain.Sop;
import com.salonreview.domain.SopAudience;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.repo.SopRepository;
import com.salonreview.repo.SopVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SopExportService}: single-file export and the all-SOPs zip. */
class SopExportServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private final SopRepository sops = mock(SopRepository.class);
    private final SopVersionRepository versions = mock(SopVersionRepository.class);
    private SopExportService service;

    @BeforeEach
    void setUp() {
        service = new SopExportService(sops, versions);
    }

    private Sop sop(Long id, String title, Long currentVersionId, SopStatus status) {
        return Sop.builder().id(id).title(title).category("Hygiene").audience(SopAudience.PROVIDER)
                .status(status).currentVersionId(currentVersionId).createdBy("owner").build();
    }

    @Test
    @DisplayName("exportOne returns null → empty when the SOP has no published version")
    void exportOneUnpublished() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(1L, "Cleaning", null, SopStatus.ACTIVE)));
        assertThat(service.exportOne(1L, BUSINESS_ID)).isEmpty();
    }

    @Test
    @DisplayName("exportOne includes body, and the RU section only when a translation exists")
    void exportOneContent() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(1L, "Cleaning Protocol", 10L, SopStatus.ACTIVE)));
        when(versions.findById(10L)).thenReturn(Optional.of(
                SopVersion.builder().id(10L).sopId(1L).versionNumber(2).body("Wipe down every station.")
                        .bodyRu("Протрите каждую станцию.").build()));

        SopExportService.Export export = service.exportOne(1L, BUSINESS_ID).orElseThrow();
        assertThat(export.filename()).isEqualTo("cleaning-protocol-1.md");
        assertThat(export.markdown()).contains("# Cleaning Protocol", "Wipe down every station.",
                "Russian translation", "Протрите каждую станцию.");
    }

    @Test
    @DisplayName("exportOne omits the RU section when there's no translation")
    void exportOneNoTranslation() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(1L, "Cleaning", 10L, SopStatus.ACTIVE)));
        when(versions.findById(10L)).thenReturn(Optional.of(
                SopVersion.builder().id(10L).sopId(1L).versionNumber(1).body("Body text.").build()));

        SopExportService.Export export = service.exportOne(1L, BUSINESS_ID).orElseThrow();
        assertThat(export.markdown()).doesNotContain("Russian translation");
    }

    @Test
    @DisplayName("exportAllAsZip includes only published ACTIVE SOPs, one entry each")
    void exportAllSkipsUnpublished() throws IOException {
        Sop published = sop(1L, "Cleaning", 10L, SopStatus.ACTIVE);
        Sop draftOnly = sop(2L, "Draft SOP", null, SopStatus.ACTIVE);
        when(sops.findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(BUSINESS_ID, SopStatus.ACTIVE))
                .thenReturn(List.of(published, draftOnly));
        when(versions.findById(10L)).thenReturn(Optional.of(
                SopVersion.builder().id(10L).sopId(1L).versionNumber(1).body("Body text.").build()));

        byte[] zip = service.exportAllAsZip(BUSINESS_ID);

        List<String> entries = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) entries.add(e.getName());
        }
        assertThat(entries).containsExactly("cleaning-1.md");
    }
}
