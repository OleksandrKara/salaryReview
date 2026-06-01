package com.salonreview.repo;

import com.salonreview.domain.Redo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RedoRepository extends JpaRepository<Redo, Long> {
    List<Redo> findAllByOrderByRedoDateDesc();
}
