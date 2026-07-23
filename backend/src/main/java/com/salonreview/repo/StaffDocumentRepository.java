package com.salonreview.repo;

import com.salonreview.domain.StaffDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, Long> {

    /** Soonest-expiring first, across every provider/manager — the admin list's default order so
     * whatever needs attention first is already at the top. */
    List<StaffDocument> findAllByOrderByExpirationDateAsc();

    /** One person's own documents, soonest-expiring first — backs the self-service "My Documents"
     * view (see StaffDocumentSelfController), so a provider/manager only ever sees their own files. */
    List<StaffDocument> findAllByProviderIdOrderByExpirationDateAsc(Long providerId);

    List<StaffDocument> findAllByAppUserIdOrderByExpirationDateAsc(Long appUserId);
}
