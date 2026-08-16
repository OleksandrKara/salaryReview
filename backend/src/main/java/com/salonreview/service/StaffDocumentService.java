package com.salonreview.service;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.domain.StaffDocument;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.StaffDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * CRUD for per-person documents (contract, license, NDA, etc.) — service providers and managers
 * only (see V49's check constraint; owners aren't covered). A renewal is always a new row, never
 * an update — see {@link com.salonreview.domain.StaffDocument}'s own note.
 */
@Service
public class StaffDocumentService {

    /** A document within this many days of its expiration (inclusive) reads as "expiring soon"
     * rather than "OK" — chosen to give an owner real lead time to request a fresh copy. */
    public static final int EXPIRING_SOON_DAYS = 30;

    public enum ExpirationStatus { OK, EXPIRING_SOON, EXPIRED }

    private final StaffDocumentRepository documents;
    private final ProviderRepository providers;
    private final AppUserRepository users;

    public StaffDocumentService(StaffDocumentRepository documents, ProviderRepository providers,
                                AppUserRepository users) {
        this.documents = documents;
        this.providers = providers;
        this.users = users;
    }

    public static ExpirationStatus statusFor(LocalDate expirationDate, LocalDate today) {
        if (expirationDate.isBefore(today)) return ExpirationStatus.EXPIRED;
        if (!expirationDate.isAfter(today.plusDays(EXPIRING_SOON_DAYS))) return ExpirationStatus.EXPIRING_SOON;
        return ExpirationStatus.OK;
    }

    /** Every document belonging to one business, soonest-expiring first. */
    public List<StaffDocument> listAll(Long businessId) {
        return documents.findAllByBusinessIdOrderByExpirationDateAsc(businessId);
    }

    /** One provider's own documents, soonest-expiring first — backs the self-service "My
     * Documents" view (see StaffDocumentSelfController), so a provider only ever sees their own
     * files, never another person's. */
    public List<StaffDocument> listForProvider(Long providerId) {
        return documents.findAllByProviderIdOrderByExpirationDateAsc(providerId);
    }

    /** Same as {@link #listForProvider}, for a manager login (identified by their own app_user id). */
    public List<StaffDocument> listForManager(Long appUserId) {
        return documents.findAllByAppUserIdOrderByExpirationDateAsc(appUserId);
    }

    public Optional<StaffDocument> get(Long id) {
        return documents.findById(id);
    }

    /** Owner-side lookup scoped to one business — used for download/update/delete ownership checks
     * so a document id from another business's table 404s instead of serving/mutating cross-tenant
     * data (see {@link com.salonreview.repo.StaffDocumentRepository#findByIdAndBusinessId}). */
    public Optional<StaffDocument> getForBusiness(Long id, Long businessId) {
        return documents.findByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public StaffDocument create(Long providerId, Long appUserId, String documentType, String label,
                                LocalDate expirationDate, String fileName, String contentType,
                                byte[] fileData, String createdBy, Long businessId) {
        if ((providerId == null) == (appUserId == null)) {
            throw new IllegalArgumentException("A document must belong to exactly one provider or manager");
        }
        if (providerId != null && providers.findByIdAndBusinessId(providerId, businessId).isEmpty()) {
            throw new IllegalArgumentException("No such provider");
        }
        if (appUserId != null) {
            AppUser u = users.findByIdAndBusinessId(appUserId, businessId)
                    .orElseThrow(() -> new IllegalArgumentException("No such user"));
            if (u.getRole() != Role.MANAGER) {
                throw new IllegalArgumentException("Documents can only be attached to a service provider or a manager");
            }
        }
        if (documentType == null || documentType.isBlank()) {
            throw new IllegalArgumentException("documentType is required");
        }
        if (expirationDate == null) {
            throw new IllegalArgumentException("expirationDate is required");
        }
        if (fileData == null || fileData.length == 0) {
            throw new IllegalArgumentException("A file is required");
        }
        return documents.save(StaffDocument.builder()
                .providerId(providerId)
                .appUserId(appUserId)
                .documentType(documentType.trim())
                .label(label == null || label.isBlank() ? null : label.trim())
                .fileName(fileName == null || fileName.isBlank() ? "document" : fileName)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .fileData(fileData)
                .expirationDate(expirationDate)
                .createdBy(createdBy)
                .build());
    }

    @Transactional
    public boolean delete(Long id, Long businessId) {
        if (documents.findByIdAndBusinessId(id, businessId).isEmpty()) return false;
        documents.deleteById(id);
        return true;
    }

    /** Corrects an existing document in place — the expiration date (a mistyped date at upload, or
     * pushing a former employee's document (see StaffDocument's own "one row per person" model) far
     * out so it stops reading as expiring/expired), and/or its type/label (fixing a typo, or just
     * renaming it), without deleting and re-uploading the same file. Each of the three fields is
     * only touched when non-null — the same "absent means leave alone" convention
     * MarketingDashboardController#updateVariant already uses — so a caller editing just the date
     * doesn't have to resend the type/label too, and vice versa. Deliberately does *not* create a
     * new row the way a real renewal would (see this class's own javadoc) — that convention is for
     * a genuinely new document (new file, new coverage period); this is just fixing fields on the
     * one already on file. Empty (not thrown) if the id doesn't exist, mirroring {@link #delete}'s
     * not-found handling. */
    @Transactional
    public Optional<StaffDocument> update(Long id, LocalDate expirationDate, String documentType, String label,
                                          Long businessId) {
        return documents.findByIdAndBusinessId(id, businessId).map(doc -> {
            if (expirationDate != null) doc.setExpirationDate(expirationDate);
            if (documentType != null) {
                if (documentType.isBlank()) throw new IllegalArgumentException("documentType cannot be blank");
                doc.setDocumentType(documentType.trim());
            }
            // "" clears the label (matches updateVariantDescription's own convention); a genuinely
            // absent field (null) leaves it untouched.
            if (label != null) doc.setLabel(label.isBlank() ? null : label.trim());
            return documents.save(doc);
        });
    }
}
