package com.salonreview.repo;

import com.salonreview.domain.PmuBookingStaffAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface PmuBookingStaffAlertRepository extends JpaRepository<PmuBookingStaffAlert, Long> {

    boolean existsByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    long countByBusinessIdAndDeliveryStatusAndDeliveredAtAfter(Long businessId, String deliveryStatus, Instant since);
}
