package com.wasteoil.settlement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算额外费用单：拒收定责后产生的额外处理费等费用，
 * 明确费用承担方与费用来源，避免处置厂、司机和商户互相推责。
 */
@Getter
@Setter
@Entity
@Table(name = "settlement_fees")
public class SettlementFee {

    /** 拒收额外处理费 */
    public static final String TYPE_EXTRA_PROCESSING = "EXTRA_PROCESSING";

    /** 商户承担 */
    public static final String BEARER_MERCHANT = "MERCHANT";
    /** 司机与车队承担 */
    public static final String BEARER_FLEET = "FLEET";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "rejection_case_id", nullable = false)
    private Long rejectionCaseId;

    /** EXTRA_PROCESSING 额外处理费 */
    @Column(name = "fee_type", nullable = false)
    private String feeType;

    @Column(nullable = false)
    private BigDecimal amount;

    /** 费用承担方：MERCHANT 商户 / FLEET 司机与车队 */
    @Column(nullable = false)
    private String bearer;

    /** 承担方为车队时的责任司机 */
    @Column(name = "bearer_driver_id")
    private Long bearerDriverId;

    /** 承担方为车队时的责任车辆 */
    @Column(name = "bearer_vehicle_id")
    private Long bearerVehicleId;

    /** 费用来源说明（如：处置厂拒收额外处理费-商户源头混入厨余） */
    @Column(name = "source_note", nullable = false)
    private String sourceNote;

    /** PENDING / CONFIRMED */
    @Column(nullable = false)
    private String status = "CONFIRMED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
