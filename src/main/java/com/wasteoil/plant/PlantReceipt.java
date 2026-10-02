package com.wasteoil.plant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 处置厂收料记录：二次称重 + 质检 + 接收/拒收决定。
 */
@Getter
@Setter
@Entity
@Table(name = "plant_receipts")
public class PlantReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    /** 到厂二次称重（公斤） */
    @Column(name = "arrival_weight_kg", nullable = false)
    private BigDecimal arrivalWeightKg;

    /** 到厂桶数 */
    @Column(name = "barrel_count", nullable = false)
    private Integer barrelCount;

    /** 质检：OK / CONTAMINATED（混入厨余） */
    @Column(name = "quality_check", nullable = false)
    private String qualityCheck;

    /** 到厂封条核验：INTACT 完好 / BROKEN 破损 / UNKNOWN 未核验 */
    @Column(name = "seal_check", nullable = false)
    private String sealCheck = "UNKNOWN";

    /** ACCEPT / REJECT */
    @Column(nullable = false)
    private String decision;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "received_by", nullable = false)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
