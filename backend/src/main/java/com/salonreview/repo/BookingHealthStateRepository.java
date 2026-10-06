package com.salonreview.repo;

import com.salonreview.domain.BookingHealthState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingHealthStateRepository extends JpaRepository<BookingHealthState, BookingHealthState.Key> {
}
