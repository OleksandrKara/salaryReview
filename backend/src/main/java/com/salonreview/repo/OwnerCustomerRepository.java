package com.salonreview.repo;

import com.salonreview.domain.OwnerCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OwnerCustomerRepository extends JpaRepository<OwnerCustomer, Long> {
    boolean existsByBusinessIdAndSquareCustomerId(Long businessId, String squareCustomerId);
    Optional<OwnerCustomer> findByIdAndBusinessId(Long id, Long businessId);
    List<OwnerCustomer> findAllByBusinessId(Long businessId);
}
