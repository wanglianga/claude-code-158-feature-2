package com.wasteoil.reject;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 拒收批次返运记录：返运路线、临时存放点、二次处理结果。
 * 一个拒收联单至多一条返运记录（无需返运时不生成）。
 */
@Getter
@Setter
@Entity
@Table(name = "reject_returns")
public class RejectReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    /** 返运路线：处置厂 → 临时存放点/二次处理单位 */
    @Column(name = "return_route", nullable = false)
    private String returnRoute;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "returned_by")
    private String returnedBy;

    @Column(name = "departed_plant_at")
    private Instant departedPlantAt;

    /** 临时存放点 */
    @Column(name = "storage_point")
    private String storagePoint;

    @Column(name = "storage_arrived_at")
    private Instant storageArrivedAt;

    @Column(name = "storage_photo_url")
    private String storagePhotoUrl;

    /** 二次处理结果：RE_ACCEPTED / DISPOSED / RETURNED_MERCHANT */
    @Enumerated(EnumType.STRING)
    @Column(name = "reprocess_result")
    private ReprocessResult reprocessResult;

    @Column(name = "reprocess_weight_kg")
    private BigDecimal reprocessWeightKg;

    @Column(name = "reprocess_note")
    private String reprocessNote;

    @Column(name = "reprocessed_at")
    private Instant reprocessedAt;

    /** DEPARTED（已离厂）/ IN_STORAGE（已入临时存放点）/ REPROCESSED（二次处理完成） */
    @Column(nullable = false)
    private String status = "DEPARTED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
