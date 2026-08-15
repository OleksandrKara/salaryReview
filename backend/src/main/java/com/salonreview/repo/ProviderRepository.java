package com.salonreview.repo;

import com.salonreview.domain.Provider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
    List<Provider> findAllByBusinessId(Long businessId);

    List<Provider> findAllByBusinessIdAndActiveTrue(Long businessId);

    /** The provider that owns the given Square team-member ID, if any. */
    @Query("select p from Provider p join p.squareTeamMemberIds m where m = :teamMemberId")
    Optional<Provider> findBySquareTeamMemberId(@Param("teamMemberId") String teamMemberId);
}
