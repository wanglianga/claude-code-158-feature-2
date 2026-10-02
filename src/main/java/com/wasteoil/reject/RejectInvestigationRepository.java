package com.wasteoil.reject;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RejectInvestigationRepository extends JpaRepository<RejectInvestigation, Long> {

    Optional<RejectInvestigation> findByManifestId(Long manifestId);
}
