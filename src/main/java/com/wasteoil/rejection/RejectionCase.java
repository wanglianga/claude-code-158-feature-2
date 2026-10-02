package com.wasteoil.rejection;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 拒收责任认定单：处置厂拒收后自动建案，追溯商户交接照片、司机封桶记录、
 * 运输轨迹与到厂检测结果，划分商户源头混入与运输中污染的责任，
 * 并记录返运路线、临时存放点与二次处理结果。
 */
@Getter
@Setter
@Entity
@Table(name = "rejection_cases")
public class RejectionCase {

    /** 待定责 */
    public static final String STATUS_PENDING = "PENDING_REVIEW";
    /** 已定责 */
    public static final String STATUS_DETERMINED = "DETERMINED";
    /** 返运中 */
    public static final String STATUS_RETURNING = "RETURNING";
    /** 二次处理完成 */
    public static final String STATUS_REPROCESSED = "REPROCESSED";
    /** 结案 */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 商户源头混入 */
    public static final String PARTY_MERCHANT = "MERCHANT_SOURCE";
    /** 运输中污染（司机与车队责任） */
    public static final String PARTY_TRANSPORT = "TRANSPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** PENDING_REVIEW / DETERMINED / RETURNING / REPROCESSED / CLOSED */
    @Column(nullable = false)
    private String status = STATUS_PENDING;

    /** 证据快照（JSON）：交接照片、封桶记录、运输轨迹、到厂检测结果 */
    @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT")
    private String evidenceJson;

    /** 系统建议责任方：MERCHANT_SOURCE / TRANSPORT / UNDETERMINED */
    @Column(name = "suggested_liability")
    private String suggestedLiability;

    /** 认定责任方：MERCHANT_SOURCE / TRANSPORT */
    @Column(name = "liability_party")
    private String liabilityParty;

    /** 定责说明 */
    @Column(name = "determination_note")
    private String determinationNote;

    /** 额外处理费（元） */
    @Column(name = "extra_fee_amount")
    private BigDecimal extraFeeAmount;

    /** 费用承担方：MERCHANT / FLEET（由责任方推导，不允许任意指定，避免推责） */
    @Column(name = "fee_bearer")
    private String feeBearer;

    @Column(name = "determined_by")
    private String determinedBy;

    @Column(name = "determined_at")
    private Instant determinedAt;

    /** 返运路线 */
    @Column(name = "return_route")
    private String returnRoute;

    /** 临时存放点 */
    @Column(name = "temp_storage_point")
    private String tempStoragePoint;

    @Column(name = "return_arranged_at")
    private Instant returnArrangedAt;

    /** 二次处理结果 */
    @Column(name = "secondary_result")
    private String secondaryResult;

    @Column(name = "reprocessed_at")
    private Instant reprocessedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
