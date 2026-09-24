package com.nicko.customer.customer;

import com.nicko.customer.customer.enums.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_profile")
@Getter
@Setter
@NoArgsConstructor
public class KycProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, unique = true)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private KycStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_tier", nullable = false)
    private KycTier requestedTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "approved_tier")
    private KycTier approvedTier;

    @Column(name = "occupation")
    private String occupation;

    @Column(name = "employer_name")
    private String employerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_of_funds", nullable = false)
    private SourceOfFunds sourceOfFunds;

    @Column(name = "expected_monthly_volume", columnDefinition = "numeric")
    private BigDecimal expectedMonthlyVolume;

    @Enumerated(EnumType.STRING)
    @Column(name = "pep_status", nullable = false)
    private PepStatus pepStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "sanctions_status", nullable = false)
    private SanctionsStatus sanctionsStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_rating", nullable = false)
    private RiskRating riskRating;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by_keycloak_id")
    private UUID reviewedByKeycloakId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
