package com.salonreview.repo;

import com.salonreview.domain.OwnerCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OwnerCustomerRepository extends JpaRepository<OwnerCustomer, Long> {
    boolean existsBySquareCustomerId(String squareCustomerId);
    Optional<OwnerCustomer> findBySquareCustomerId(String squareCustomerId);
}
