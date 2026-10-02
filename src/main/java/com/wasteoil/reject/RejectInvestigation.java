package com.wasteoil.reject;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 拒收责任调查。处置厂拒收后由系统自动发起：
 * 追溯商户交接照片、司机封桶记录、运输轨迹、到厂检测结果，固化证据快照并给出责任建议；
 * 回收公司认定责任方（商户源头 / 司机车队）、额外处理费用与费用来源。
 */
@Getter
@Setter
@Entity
@Table(name = "reject_investigations")
public class RejectInvestigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    /** OPEN（待回收公司认定）/ CONFIRMED（已认定） */
    @Column(nullable = false)
    private String status = "OPEN";

    // ---- 追溯证据快照（发起鉴定时固化，事后原始数据变更不影响定责依据） ----

    /** 商户交接照片数（到店扫码核验留存） */
    @Column(name = "handover_photo_count", nullable = false)
    private Integer handoverPhotoCount = 0;

    /** 称重现场照片数 */
    @Column(name = "weighing_photo_count", nullable = false)
    private Integer weighingPhotoCount = 0;

    /** 已加封封条的桶数 */
    @Column(name = "sealed_barrel_count", nullable = false)
    private Integer sealedBarrelCount = 0;

    /** 本趟装车桶数 */
    @Column(name = "total_barrel_count", nullable = false)
    private Integer totalBarrelCount = 0;

    /** 是否全部桶已封桶 */
    @Column(name = "all_sealed", nullable = false)
    private Boolean allSealed = false;

    /** 到厂封条是否完好（处置厂核验） */
    @Column(name = "seal_intact_at_plant")
    private Boolean sealIntactAtPlant;

    /** 到厂检测结果描述 */
    @Column(name = "plant_check_detail")
    private String plantCheckDetail;

    /** 运输轨迹点数 */
    @Column(name = "track_point_count", nullable = false)
    private Integer trackPointCount = 0;

    /** 运输途中异常停留次数 */
    @Column(name = "abnormal_stop_count", nullable = false)
    private Integer abnormalStopCount = 0;

    /** 异常停留明细 */
    @Column(name = "abnormal_stop_detail")
    private String abnormalStopDetail;

    /** 厨余残渣等污染情况 */
    @Column(name = "contamination_note")
    private String contaminationNote;

    // ---- 系统责任建议 ----

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_liability")
    private SuggestedLiability suggestedLiability;

    @Column(name = "suggested_reason")
    private String suggestedReason;

    // ---- 回收公司认定结论 ----

    @Enumerated(EnumType.STRING)
    @Column(name = "liability_party")
    private LiabilityParty liabilityParty;

    /** 额外处理费用（元） */
    @Column(name = "extra_fee")
    private BigDecimal extraFee;

    /** 费用承担方：MERCHANT / DRIVER_FLEET */
    @Column(name = "fee_payer")
    private String feePayer;

    /** 费用来源说明（什么费用、为何由该方承担），结算单原样展示，避免互相推责 */
    @Column(name = "fee_source")
    private String feeSource;

    /** 无需返运（如厂内移交无害化处理） */
    @Column(name = "no_return_required", nullable = false)
    private Boolean noReturnRequired = false;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
