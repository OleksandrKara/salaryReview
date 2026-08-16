package com.salonreview.repo;

import com.salonreview.domain.StaffDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, Long> {

    /** One person's own documents, soonest-expiring first — backs the self-service "My Documents"
     * view (see StaffDocumentSelfController), so a provider/manager only ever sees their own files.
     * Not business-scoped: providerId/appUserId here is always the caller's own id (never taken from
     * request input), so it's inherently already scoped to whatever business that person belongs to. */
    List<StaffDocument> findAllByProviderIdOrderByExpirationDateAsc(Long providerId);

    List<StaffDocument> findAllByAppUserIdOrderByExpirationDateAsc(Long appUserId);

    /** Owner admin list, soonest-expiring first, scoped to one business. staff_documents has no
     * business_id column of its own — provider_id/app_user_id are both real FKs to already
     * business-scoped tables (unlike e.g. provider_visit's string-only provider_ref), so this joins
     * through whichever of the two is set, same idiom as RedoRepository/ManualAdjustmentRepository. */
    @Query("select d from StaffDocument d "
            + "left join Provider p on p.id = d.providerId "
            + "left join AppUser u on u.id = d.appUserId "
            + "where p.businessId = :businessId or u.businessId = :businessId "
            + "order by d.expirationDate asc")
    List<StaffDocument> findAllByBusinessIdOrderByExpirationDateAsc(@Param("businessId") Long businessId);

    /** Single-document lookup scoped to a business — the owner-side get/update/delete/download
     * ownership check, same join as above. */
    @Query("select d from StaffDocument d "
            + "left join Provider p on p.id = d.providerId "
            + "left join AppUser u on u.id = d.appUserId "
            + "where d.id = :id and (p.businessId = :businessId or u.businessId = :businessId)")
    Optional<StaffDocument> findByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);
}
