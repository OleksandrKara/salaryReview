package com.salonreview.rag;

import com.salonreview.domain.KbRequest;
import com.salonreview.domain.KbRequestStatus;
import com.salonreview.domain.KbRequestTarget;
import com.salonreview.repo.KbRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Knowledge-gap requests filed when the assistant can't answer. Owner/manager create; owner lists,
 * triages (resolve/dismiss/reopen), and deletes. Marking RESOLVED stamps who/when; reopening clears it.
 */
@Service
public class KbRequestService {

    private final KbRequestRepository repo;

    public KbRequestService(KbRequestRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public KbRequest create(String question, String note, KbRequestTarget target, String by, Long businessId) {
        return repo.save(KbRequest.builder()
                .businessId(businessId)
                .question(question.trim())
                .note(blankToNull(note))
                .target(target == null ? KbRequestTarget.UNSURE : target)
                .status(KbRequestStatus.OPEN)
                .requestedBy(by)
                .build());
    }

    public List<KbRequest> list(Long businessId) {
        return repo.findAllByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    public long openCount(Long businessId) {
        return repo.countByBusinessIdAndStatus(businessId, KbRequestStatus.OPEN);
    }

    /** Set the status; RESOLVED records who/when, any other status clears the resolution stamp. */
    @Transactional
    public Optional<KbRequest> setStatus(Long id, KbRequestStatus status, String by, Long businessId) {
        return repo.findByIdAndBusinessId(id, businessId).map(r -> {
            r.setStatus(status);
            if (status == KbRequestStatus.RESOLVED) {
                r.setResolvedAt(Instant.now());
                r.setResolvedBy(by);
            } else {
                r.setResolvedAt(null);
                r.setResolvedBy(null);
            }
            return repo.save(r);
        });
    }

    @Transactional
    public boolean delete(Long id, Long businessId) {
        if (repo.findByIdAndBusinessId(id, businessId).isEmpty()) return false;
        repo.deleteById(id);
        return true;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
