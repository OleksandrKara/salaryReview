package com.salonreview.kb;

import com.salonreview.domain.KbArticle;
import com.salonreview.repo.KbArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link KbExportService}: single-file export and the all-articles zip. */
class KbExportServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private final KbArticleRepository repo = mock(KbArticleRepository.class);
    private KbExportService service;

    @BeforeEach
    void setUp() {
        service = new KbExportService(repo);
    }

    private KbArticle article(Long id, String title, String body, String bodyRu) {
        return KbArticle.builder().id(id).title(title).category("FAQ").body(body).bodyRu(bodyRu)
                .createdBy("owner").build();
    }

    @Test
    @DisplayName("exportOne returns empty when the article doesn't exist")
    void exportOneMissing() {
        when(repo.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.empty());
        assertThat(service.exportOne(1L, BUSINESS_ID)).isEmpty();
    }

    @Test
    @DisplayName("exportOne includes body, and the RU section only when a translation exists")
    void exportOneContent() {
        when(repo.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(
                article(1L, "Refund Policy", "Refunds within 7 days.", "Возврат в течение 7 дней.")));

        KbExportService.Export export = service.exportOne(1L, BUSINESS_ID).orElseThrow();
        assertThat(export.filename()).isEqualTo("refund-policy-1.md");
        assertThat(export.markdown()).contains("# Refund Policy", "Refunds within 7 days.",
                "Russian translation", "Возврат в течение 7 дней.");
    }

    @Test
    @DisplayName("exportOne omits the RU section when there's no translation")
    void exportOneNoTranslation() {
        when(repo.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(article(1L, "Refund Policy", "Body text.", null)));
        KbExportService.Export export = service.exportOne(1L, BUSINESS_ID).orElseThrow();
        assertThat(export.markdown()).doesNotContain("Russian translation");
    }

    @Test
    @DisplayName("exportAllAsZip includes one entry per article")
    void exportAllZips() throws IOException {
        List<KbArticle> articles = new ArrayList<>();
        articles.add(article(1L, "Refund Policy", "Body one.", null));
        articles.add(article(2L, "Cancellation Policy", "Body two.", null));
        when(repo.findAllByBusinessIdOrderByCategoryAscTitleAsc(BUSINESS_ID)).thenReturn(articles);

        byte[] zip = service.exportAllAsZip(BUSINESS_ID);

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) entries.add(e.getName());
        }
        assertThat(entries).containsExactlyInAnyOrder("refund-policy-1.md", "cancellation-policy-2.md");
    }
}
