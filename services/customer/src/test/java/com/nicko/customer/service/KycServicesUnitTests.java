package com.nicko.customer.service;

import com.nicko.customer.config.DocumentProtection;
import com.nicko.customer.dto.KycDocumentRequest;
import com.nicko.customer.dto.KycProfileRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.KycDocument;
import com.nicko.customer.entity.KycProfile;
import com.nicko.customer.entity.enums.*;
import com.nicko.customer.mapper.KycDocumentMapper;
import com.nicko.customer.mapper.KycProfileMapper;
import com.nicko.customer.repository.KycDocumentRepository;
import com.nicko.customer.repository.KycProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KycServicesUnitTests {
    @Test void profileCreateInitializesConservativeStatusesAndRejectsDuplicateOrTierZero() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        KycProfileRepository repository = mock(KycProfileRepository.class);
        KycProfileMapper mapper = mock(KycProfileMapper.class);
        KycProfileService service = new KycProfileService(ownership, mock(KycAccess.class), repository,
                mock(KycDocumentRepository.class), mapper, mock(OutboxEventService.class), mock(CustomerAuditService.class));
        UUID user = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.lock(user)).thenReturn(customer);
        when(repository.findByCustomerId(customer.getId())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        KycProfileRequest request = new KycProfileRequest(KycTier.TIER_1, "worker", null, SourceOfFunds.SALARY, null);
        service.create(user, request);
        verify(repository).saveAndFlush(argThat(p -> p.getStatus() == KycStatus.NOT_STARTED
                && p.getRiskRating() == RiskRating.HIGH && p.getPepStatus() == PepStatus.NOT_SCREENED
                && p.getSanctionsStatus() == SanctionsStatus.NOT_SCREENED));
        when(repository.findByCustomerId(customer.getId())).thenReturn(Optional.of(new KycProfile()));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.create(user, request)).getStatusCode());
        when(repository.findByCustomerId(customer.getId())).thenReturn(Optional.empty());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.create(user,
                new KycProfileRequest(KycTier.TIER_0, null, null, SourceOfFunds.SALARY, null))).getStatusCode());
    }

    @Test void documentCreateHashesAndEncryptsNumberAndRejectsDuplicate() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        KycAccess access = mock(KycAccess.class);
        KycDocumentRepository repository = mock(KycDocumentRepository.class);
        KycDocumentMapper mapper = mock(KycDocumentMapper.class);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        KycDocumentService service = new KycDocumentService(ownership, access, repository, mapper,
                new DocumentProtection(key, key), mock(CustomerAuditService.class));
        UUID user = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        KycProfile profile = new KycProfile(); profile.setId(UUID.randomUUID()); profile.setStatus(KycStatus.NOT_STARTED);
        when(ownership.lock(user)).thenReturn(customer);
        when(access.require(customer.getId())).thenReturn(profile);
        when(repository.existsByDocumentNumberHash(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        KycDocumentRequest request = new KycDocumentRequest(DocumentType.NATIONAL_ID, " ab123 ", "KE",
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), "front", null);
        service.create(user, request);
        verify(repository).saveAndFlush(argThat(d -> d.getKycProfile() == profile
                && d.getVerificationStatus() == DocumentVerificationStatus.PENDING
                && d.getDocumentNumberHash() != null && !d.getDocumentNumberHash().contains("ab123")
                && d.getDocumentNumberEncrypted().startsWith("v1:") && !d.getDocumentNumberEncrypted().contains("ab123")));

        when(repository.existsByDocumentNumberHash(anyString())).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.create(user, request)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.create(user,
                new KycDocumentRequest(DocumentType.NATIONAL_ID, "ab123", "KE", LocalDate.of(2025, 1, 1),
                        LocalDate.of(2024, 1, 1), "front", null))).getStatusCode());
    }
}
