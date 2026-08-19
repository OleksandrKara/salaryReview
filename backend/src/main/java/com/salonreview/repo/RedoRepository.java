package com.salonreview.repo;

import com.salonreview.domain.Redo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RedoRepository extends JpaRepository<Redo, Long> {
    /** Joins on {@code original_provider_id} — a redo's original and redo provider are always the
     * same business in practice (commission just moves between two of that business's own
     * providers), so either FK works as the tenant-scoping join. */
    @Query("select r from Redo r join Provider p on p.id = r.originalProviderId "
            + "where p.businessId = :businessId order by r.redoDate desc")
    List<Redo> findAllByBusinessIdOrderByRedoDateDesc(@Param("businessId") Long businessId);

    @Query("select r from Redo r join Provider p on p.id = r.originalProviderId "
            + "where r.id = :id and p.businessId = :businessId")
    Optional<Redo> findByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);
}
