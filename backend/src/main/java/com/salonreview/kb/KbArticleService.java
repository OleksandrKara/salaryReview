package com.salonreview.kb;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.Role;
import com.salonreview.domain.SyncStatus;
import com.salonreview.rag.RagIngestionService;
import com.salonreview.repo.KbArticleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CRUD for KB articles, plus content-hash change detection and per-article read authorization.
 * Never auto-syncs to RAG — sync is always an explicit action ({@link KbSyncService}). Delete
 * retires the linked RAG document first so no orphan remains.
 */
@Service
public class KbArticleService {

    private final KbArticleRepository repo;
    private final ObjectProvider<RagIngestionService> ragIngestionProvider;

    public KbArticleService(KbArticleRepository repo,
                            ObjectProvider<RagIngestionService> ragIngestionProvider) {
        this.repo = repo;
        this.ragIngestionProvider = ragIngestionProvider;
    }

    /** Articles visible to the caller: OWNER/MANAGER see all; others only where their role is allowed. */
    public List<KbArticle> list(Role role) {
        List<KbArticle> all = repo.findAllByOrderByCategoryAscTitleAsc();
        if (isAdmin(role)) return all;
        return all.stream().filter(a -> a.getVisibleRoles().contains(role)).toList();
    }

    /** A single article, only if the caller's role may read it. */
    public Optional<KbArticle> get(Long id, Role role) {
        return repo.findById(id)
                .filter(a -> isAdmin(role) || a.getVisibleRoles().contains(role));
    }

    @Transactional
    public KbArticle create(String title, String category, String body, List<Role> visibleRoles,
                            String createdBy) {
        String safeBody = body == null ? "" : body;
        KbArticle a = KbArticle.builder()
                .title(title)
                .category(category)
                .body(safeBody)
                .visibleRoles(normalizeRoles(visibleRoles))
                .contentHash(contentHash(safeBody))
                .syncStatus(SyncStatus.NOT_SYNCED)
                .createdBy(createdBy)
                .build();
        return repo.save(a);
    }

    /**
     * Update. Recomputes the hash; if the body changed, marks the article {@code CHANGED} (when it
     * has a prior RAG doc) or {@code NOT_SYNCED} (when it never synced). Never syncs as a side effect.
     */
    @Transactional
    public Optional<KbArticle> update(Long id, String title, String category, String body,
                                      List<Role> visibleRoles) {
        return repo.findById(id).map(a -> {
            a.setTitle(title);
            a.setCategory(category);
            String safeBody = body == null ? "" : body;
            a.setBody(safeBody);
            if (visibleRoles != null) a.setVisibleRoles(normalizeRoles(visibleRoles));
            String newHash = contentHash(safeBody);
            if (!newHash.equals(a.getContentHash())) {
                a.setContentHash(newHash);
                a.setSyncStatus(a.getRagDocId() != null ? SyncStatus.CHANGED : SyncStatus.NOT_SYNCED);
            }
            return repo.save(a);
        });
    }

    /** Delete the article, retiring its RAG document first (no orphaned RAG doc). */
    @Transactional
    public boolean delete(Long id, String deletedBy) {
        return repo.findById(id).map(a -> {
            if (a.getRagDocId() != null) {
                RagIngestionService rag = ragIngestionProvider.getIfAvailable();
                if (rag != null) rag.delete(a.getRagDocId(), deletedBy);
            }
            repo.delete(a);
            return true;
        }).orElse(false);
    }

    private static boolean isAdmin(Role role) {
        return role == Role.OWNER || role == Role.MANAGER;
    }

    /**
     * Normalize the assigned roles: always include OWNER and MANAGER (they administer everything —
     * the "manager = admin" decision), dedupe, and default to {@code {OWNER, MANAGER}} when none
     * are given (not exposed to providers until explicitly shared).
     */
    private static List<Role> normalizeRoles(List<Role> input) {
        Set<Role> roles = new LinkedHashSet<>();
        roles.add(Role.OWNER);
        roles.add(Role.MANAGER);
        if (input != null) roles.addAll(input);
        return new ArrayList<>(roles);
    }

    /** SHA-256 hex of the body — shared with {@link KbSyncService}. */
    public static String contentHash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
