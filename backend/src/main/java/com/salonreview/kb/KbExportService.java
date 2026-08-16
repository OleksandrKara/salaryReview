package com.salonreview.kb;

import com.salonreview.domain.KbArticle;
import com.salonreview.repo.KbArticleRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a KB article as a standalone Markdown file, for an owner to keep an offline copy or share
 * outside the app — mirrors {@link com.salonreview.sop.SopExportService} for SOPs.
 */
@Service
public class KbExportService {

    private final KbArticleRepository repo;

    public KbExportService(KbArticleRepository repo) {
        this.repo = repo;
    }

    public record Export(String filename, String markdown) {}

    public Optional<Export> exportOne(Long id, Long businessId) {
        return repo.findByIdAndBusinessId(id, businessId).map(KbExportService::toExport);
    }

    /** Zips every KB article for one business. */
    public byte[] exportAllAsZip(Long businessId) {
        List<KbArticle> all = repo.findAllByBusinessIdOrderByCategoryAscTitleAsc(businessId);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (KbArticle a : all) {
                Export export = toExport(a);
                zip.putNextEntry(new ZipEntry(export.filename()));
                zip.write(export.markdown().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    private static Export toExport(KbArticle a) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(a.getTitle()).append("\n\n");
        md.append("- **Category:** ").append(a.getCategory()).append("\n\n");
        md.append(a.getBody() == null ? "" : a.getBody().trim()).append("\n");
        if (a.getBodyRu() != null && !a.getBodyRu().isBlank()) {
            md.append("\n---\n\n## Russian translation\n\n").append(a.getBodyRu().trim()).append("\n");
        }
        return new Export(filenameFor(a), md.toString());
    }

    // Suffixed with the id so two articles that slug to the same name don't collide inside the zip.
    private static String filenameFor(KbArticle a) {
        String slug = a.getTitle().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return (slug.isBlank() ? "article" : slug) + "-" + a.getId() + ".md";
    }
}
