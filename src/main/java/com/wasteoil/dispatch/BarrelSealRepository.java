package com.wasteoil.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BarrelSealRepository extends JpaRepository<BarrelSeal, Long> {

    List<BarrelSeal> findByDispatchId(Long dispatchId);

    Optional<BarrelSeal> findByDispatchIdAndBarrelId(Long dispatchId, Long barrelId);

    boolean existsByDispatchIdAndBarrelId(Long dispatchId, Long barrelId);
}
