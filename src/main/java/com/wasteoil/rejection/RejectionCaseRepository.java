package com.wasteoil.rejection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RejectionCaseRepository extends JpaRepository<RejectionCase, Long> {

    Optional<RejectionCase> findByManifestId(Long manifestId);

    List<RejectionCase> findByStatus(String status);

    List<RejectionCase> findByStoreIdOrderByCreatedAtDesc(Long storeId);
}
