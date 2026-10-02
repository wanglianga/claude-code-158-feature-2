package com.wasteoil.reject;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RejectReturnRepository extends JpaRepository<RejectReturn, Long> {

    Optional<RejectReturn> findByManifestId(Long manifestId);
}
