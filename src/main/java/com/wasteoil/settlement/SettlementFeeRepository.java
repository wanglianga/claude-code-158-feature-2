package com.wasteoil.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementFeeRepository extends JpaRepository<SettlementFee, Long> {

    List<SettlementFee> findByManifestId(Long manifestId);

    List<SettlementFee> findByStoreIdOrderByCreatedAtDesc(Long storeId);
}
