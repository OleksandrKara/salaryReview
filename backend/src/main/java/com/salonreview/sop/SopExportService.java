package com.salonreview.sop;

import com.salonreview.domain.Sop;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.repo.SopRepository;
import com.salonreview.repo.SopVersionRepository;
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
 * Exports a SOP's current published version as a standalone Markdown file, for an owner to keep an
 * offline copy or share outside the app. Only a published version has content to export — a
 * draft-only or archived SOP is skipped (draft has nothing live, archived is retired policy).
 */
@Service
public class SopExportService {

    private final SopRepository sops;
    private final SopVersionRepository versions;

    public SopExportService(SopRepository sops, SopVersionRepository versions) {
        this.sops = sops;
        this.versions = versions;
    }

    public record Export(String filename, String markdown) {}

    public Optional<Export> exportOne(Long id) {
        return sops.findById(id).flatMap(this::toExport);
    }

    /** Zips every ACTIVE, published SOP — the same corpus shown in the admin sync section. */
    public byte[] exportAllAsZip() {
        List<Sop> active = sops.findByStatusOrderByPriorityAscCategoryAscTitleAsc(SopStatus.ACTIVE);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Sop s : active) {
                Optional<Export> export = toExport(s);
                if (export.isEmpty()) continue;
                zip.putNextEntry(new ZipEntry(export.get().filename()));
                zip.write(export.get().markdown().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    private Optional<Export> toExport(Sop s) {
        if (s.getCurrentVersionId() == null) return Optional.empty();
        SopVersion v = versions.findById(s.getCurrentVersionId()).orElse(null);
        if (v == null) return Optional.empty();

        StringBuilder md = new StringBuilder();
        md.append("# ").append(s.getTitle()).append("\n\n");
        md.append("- **Category:** ").append(s.getCategory()).append("\n");
        md.append("- **Audience:** ").append(s.getAudience().name()).append("\n");
        md.append("- **Version:** ").append(v.getVersionNumber()).append("\n\n");
        md.append(v.getBody() == null ? "" : v.getBody().trim()).append("\n");
        if (v.getBodyRu() != null && !v.getBodyRu().isBlank()) {
            md.append("\n---\n\n## Russian translation\n\n").append(v.getBodyRu().trim()).append("\n");
        }

        return Optional.of(new Export(filenameFor(s), md.toString()));
    }

    // Suffixed with the id so two SOPs that slug to the same name don't collide inside the zip.
    private static String filenameFor(Sop s) {
        String slug = s.getTitle().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return (slug.isBlank() ? "sop" : slug) + "-" + s.getId() + ".md";
    }
}
