package com.salonreview.repo;

import com.salonreview.domain.ExpenseCategoryDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategoryDefinition, Long> {

    List<ExpenseCategoryDefinition> findAllByOrderBySortOrderAscLabelAsc();

    Optional<ExpenseCategoryDefinition> findByCode(String code);

    boolean existsByCode(String code);
}
