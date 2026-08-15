package com.salonreview.repo;

import com.salonreview.domain.TierGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TierGrantRepository extends JpaRepository<TierGrant, Long> {

    /** {@code provider_id} has no mapped @ManyToOne here (plain FK column), so tenant scoping is an
     * explicit join against {@code providers.business_id} rather than a path expression. */
    @Query("select g from TierGrant g join Provider p on p.id = g.providerId "
            + "where g.year = :year and g.month = :month and p.businessId = :businessId")
    List<TierGrant> findByBusinessIdAndYearAndMonth(@Param("businessId") Long businessId,
                                                     @Param("year") int year, @Param("month") int month);

    Optional<TierGrant> findByProviderIdAndYearAndMonth(Long providerId, int year, int month);

    void deleteByProviderIdAndYearAndMonth(Long providerId, int year, int month);
}
