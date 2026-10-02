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

    /** ACCEPT / REJECT */
    @Column(nullable = false)
    private String decision;

    @Column(name = "reject_reason")
    private String rejectReason;

    /** 到厂时封条是否完好（拒收时由处置厂核验，作为运输环节责任判定依据） */
    @Column(name = "seal_intact")
    private Boolean sealIntact;

    /** 封条异常说明（破损/缺失封条编号等） */
    @Column(name = "seal_note")
    private String sealNote;

    @Column(name = "received_by", nullable = false)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
