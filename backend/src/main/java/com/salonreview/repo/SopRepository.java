package com.salonreview.repo;

import com.salonreview.domain.Sop;
import com.salonreview.domain.SopStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SopRepository extends JpaRepository<Sop, Long> {

    /** Owner view — all SOPs including drafts/archived. */
    List<Sop> findAllByOrderByCategoryAscTitleAsc();

    /** Staff view base set — active SOPs (further filtered by audience + has-published in the service). */
    List<Sop> findByStatusOrderByCategoryAscTitleAsc(SopStatus status);
}
