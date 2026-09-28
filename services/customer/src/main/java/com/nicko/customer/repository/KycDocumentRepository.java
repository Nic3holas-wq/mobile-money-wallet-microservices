package com.nicko.customer.repository;

import com.nicko.customer.customer.KycDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {
    Page<KycDocument> findByKycProfileId(UUID profileId, Pageable pageable);
    Optional<KycDocument> findByIdAndKycProfileId(UUID id, UUID profileId);
    java.util.List<KycDocument> findByKycProfileId(UUID profileId);
    boolean existsByDocumentNumberHash(String hash);
    boolean existsByDocumentNumberHashAndIdNot(String hash, UUID id);
}
