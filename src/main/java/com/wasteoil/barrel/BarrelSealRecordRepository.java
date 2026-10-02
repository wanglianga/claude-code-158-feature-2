package com.wasteoil.barrel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BarrelSealRecordRepository extends JpaRepository<BarrelSealRecord, Long> {

    List<BarrelSealRecord> findByDispatchIdOrderBySealedAtAsc(Long dispatchId);

    boolean existsByDispatchIdAndBarrelId(Long dispatchId, Long barrelId);
}
