package com.salonreview.sop;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.domain.Sop;
import com.salonreview.domain.SopAcknowledgment;
import com.salonreview.domain.SopAudience;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.domain.SopVersionStatus;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.SopAcknowledgmentRepository;
import com.salonreview.repo.SopRepository;
import com.salonreview.repo.SopVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SOP authoring, versioning/publishing, audience-scoped reads, acknowledgment, and the owner roster.
 *
 * <p>Acknowledgment correctness is declarative: acks are keyed to {@code sop_version_id} and "have
 * you acknowledged?" always resolves against {@code sop.currentVersionId}, so publishing a new
 * version automatically requires fresh acknowledgment with no special-casing. Audience is evaluated
 * fresh on every read/acknowledge — the UI does no authorization of its own.
 */
@Service
public class SopService {

    private final SopRepository sops;
    private final SopVersionRepository versions;
    private final SopAcknowledgmentRepository acks;
    private final AppUserRepository users;

    public SopService(SopRepository sops, SopVersionRepository versions,
                      SopAcknowledgmentRepository acks, AppUserRepository users) {
        this.sops = sops;
        this.versions = versions;
        this.acks = acks;
        this.users = users;
    }

    // ---------------------------------------------------------------- authoring (owner)

    /** Create a SOP and its first draft version (version 1). */
    @Transactional
    public Sop create(String title, String titleRu, String category, SopAudience audience, Integer priority,
                      String body, String bodyRu, String by, Long businessId) {
        Sop.SopBuilder builder = Sop.builder()
                .title(title).titleRu(blankToNull(titleRu)).category(category).audience(audience)
                .status(SopStatus.ACTIVE).createdBy(by).businessId(businessId);
        if (priority != null) builder.priority(priority);
        Sop sop = sops.save(builder.build());
        versions.save(SopVersion.builder()
                .sopId(sop.getId()).versionNumber(1).body(body == null ? "" : body).bodyRu(blankToNull(bodyRu))
                .status(SopVersionStatus.DRAFT).createdBy(by).build());
        return sop;
    }

    /** Update title/category/audience/priority on the SOP itself (not content). Applies immediately. */
    @Transactional
    public Optional<Sop> updateMeta(Long id, String title, String titleRu, String category, SopAudience audience,
                                    Integer priority, Long businessId) {
        return sops.findByIdAndBusinessId(id, businessId).map(s -> {
            s.setTitle(title);
            s.setTitleRu(blankToNull(titleRu));
            s.setCategory(category);
            s.setAudience(audience);
            if (priority != null) s.setPriority(priority);
            return sops.save(s);
        });
    }

    /**
     * Add a new draft version (max + 1). Does not change the live version. changeNote/changeNoteRu
     * describe what's different from the previous version — shown to staff reviewing v2+ as a short
     * notice; blank means no notice.
     */
    @Transactional
    public Optional<SopVersion> addVersion(Long id, String body, String bodyRu,
                                           String changeNote, String changeNoteRu, String by, Long businessId) {
        if (sops.findByIdAndBusinessId(id, businessId).isEmpty()) return Optional.empty();
        int next = versions.findTopBySopIdOrderByVersionNumberDesc(id)
                .map(v -> v.getVersionNumber() + 1).orElse(1);
        return Optional.of(versions.save(SopVersion.builder()
                .sopId(id).versionNumber(next).body(body == null ? "" : body).bodyRu(blankToNull(bodyRu))
                .changeNote(blankToNull(changeNote)).changeNoteRu(blankToNull(changeNoteRu))
                .status(SopVersionStatus.DRAFT).createdBy(by).build()));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Publish a draft version: make it live (current) and mark it PUBLISHED. */
    @Transactional
    public Optional<Sop> publish(Long id, Long versionId, Long businessId) {
        Optional<Sop> sopOpt = sops.findByIdAndBusinessId(id, businessId);
        if (sopOpt.isEmpty()) return Optional.empty();
        SopVersion v = versions.findById(versionId)
                .filter(x -> x.getSopId().equals(id))
                .orElse(null);
        if (v == null) return Optional.empty();
        if (v.getStatus() == SopVersionStatus.PUBLISHED) {
            throw new AlreadyPublishedException();
        }
        v.setStatus(SopVersionStatus.PUBLISHED);
        versions.save(v);
        Sop sop = sopOpt.get();
        sop.setCurrentVersionId(versionId);
        return Optional.of(sops.save(sop));
    }

    @Transactional
    public Optional<Sop> setStatus(Long id, SopStatus status, Long businessId) {
        return sops.findByIdAndBusinessId(id, businessId).map(s -> {
            s.setStatus(status);
            return sops.save(s);
        });
    }

    /** Full version history (owner), empty when the SOP doesn't exist or isn't this business's. */
    public List<SopVersion> versionHistory(Long id, Long businessId) {
        if (sops.findByIdAndBusinessId(id, businessId).isEmpty()) return List.of();
        return versions.findBySopIdOrderByVersionNumberAsc(id);
    }

    // ---------------------------------------------------------------- reads

    /**
     * SOPs visible to the caller within one business. Owner sees all; managers/providers see only
     * ACTIVE SOPs with a published version whose audience includes their role, each with the
     * current version content and the caller's acknowledgment state.
     */
    public List<SopListItem> list(Role role, Long userId, Long businessId) {
        if (role == Role.OWNER) {
            return sops.findAllByBusinessIdOrderByPriorityAscCategoryAscTitleAsc(businessId).stream()
                    .map(s -> item(s, null))
                    .toList();
        }
        List<SopListItem> out = new ArrayList<>();
        for (Sop s : sops.findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(businessId, SopStatus.ACTIVE)) {
            if (s.getCurrentVersionId() == null || !s.getAudience().includes(role)) continue;
            out.add(item(s, userId));
        }
        return out;
    }

    /**
     * A single SOP for the shareable-link detail page, subject to the exact same visibility rule
     * as {@link #list}: owner sees anything; managers/providers only an ACTIVE SOP with a
     * published version whose audience includes their role. Empty for "doesn't exist", "exists in
     * another business", and "exists but not visible to you" alike — the detail page shows one
     * generic message either way, same as {@link com.salonreview.web.KbArticleController#get} does
     * for KB articles.
     */
    public Optional<SopListItem> getVisible(Long id, Role role, Long userId, Long businessId) {
        Optional<Sop> sopOpt = sops.findByIdAndBusinessId(id, businessId);
        if (sopOpt.isEmpty()) return Optional.empty();
        Sop s = sopOpt.get();
        if (role == Role.OWNER) return Optional.of(item(s, null));
        if (s.getCurrentVersionId() == null || s.getStatus() != SopStatus.ACTIVE || !s.getAudience().includes(role)) {
            return Optional.empty();
        }
        return Optional.of(item(s, userId));
    }

    public SopListItem item(Sop s, Long userIdOrNull) {
        SopVersion current = s.getCurrentVersionId() == null ? null
                : versions.findById(s.getCurrentVersionId()).orElse(null);
        boolean acked = false;
        Instant ackedAt = null;
        if (userIdOrNull != null && current != null) {
            Optional<SopAcknowledgment> a = acks.findBySopVersionIdAndUserId(current.getId(), userIdOrNull);
            acked = a.isPresent();
            ackedAt = a.map(SopAcknowledgment::getAcknowledgedAt).orElse(null);
        }
        return new SopListItem(s, current, acked, ackedAt);
    }

    // ---------------------------------------------------------------- acknowledge (staff)

    /**
     * Record the caller's acknowledgment of the SOP's current version. Idempotent; rejects when the
     * caller's role isn't in the audience or there is no published version. Returns the refreshed
     * list item; empty when the SOP doesn't exist.
     */
    @Transactional
    public Optional<SopListItem> acknowledge(Long id, Long userId, Role role, Long businessId) {
        Optional<Sop> sopOpt = sops.findByIdAndBusinessId(id, businessId);
        if (sopOpt.isEmpty()) return Optional.empty();
        Sop sop = sopOpt.get();
        if (sop.getStatus() != SopStatus.ACTIVE || !sop.getAudience().includes(role)) {
            throw new OutOfAudienceException();
        }
        Long cv = sop.getCurrentVersionId();
        if (cv == null) throw new NothingToAcknowledgeException();
        if (!acks.existsBySopVersionIdAndUserId(cv, userId)) {
            acks.save(SopAcknowledgment.builder().sopVersionId(cv).userId(userId).build());
        }
        return Optional.of(item(sop, userId));
    }

    // ---------------------------------------------------------------- roster (owner)

    /** Every active user in the SOP's audience with their ack state for the current version. */
    public List<RosterEntry> roster(Long id, Long businessId) {
        Sop sop = sops.findByIdAndBusinessId(id, businessId).orElse(null);
        if (sop == null) return List.of();
        List<AppUser> audience = users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(
                businessId, sop.getAudience().roles());
        Map<Long, Instant> ackedAt = new HashMap<>();
        if (sop.getCurrentVersionId() != null) {
            for (SopAcknowledgment a : acks.findBySopVersionId(sop.getCurrentVersionId())) {
                ackedAt.put(a.getUserId(), a.getAcknowledgedAt());
            }
        }
        return audience.stream()
                .map(u -> new RosterEntry(u.getId(), u.getUsername(), u.getRole(),
                        ackedAt.containsKey(u.getId()), ackedAt.get(u.getId())))
                .toList();
    }

    // ---------------------------------------------------------------- carriers + errors

    public record SopListItem(Sop sop, SopVersion currentVersion, boolean acknowledged, Instant acknowledgedAt) {}

    public record RosterEntry(Long userId, String username, Role role, boolean acknowledged, Instant acknowledgedAt) {}

    /** Re-publishing an already-published version → 409. */
    public static class AlreadyPublishedException extends RuntimeException {}

    /** Caller's role not in the SOP's audience → 403. */
    public static class OutOfAudienceException extends RuntimeException {}

    /** Acknowledge with no published version → 422. */
    public static class NothingToAcknowledgeException extends RuntimeException {}
}
