package com.wasteoil.settlement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算单。联单 VERIFIED 后按到厂重量生成；商户质疑时冻结，争议解决后调整。
 */
@Getter
@Setter
@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** 结算重量（默认到厂重量，争议解决后可调整） */
    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private BigDecimal amount;

    /** PENDING / FROZEN / CONFIRMED */
    @Column(nullable = false)
    private String status = "PENDING";

    /** 单据类型：PURCHASE（废油收购款，商户应收）/ REJECT_FEE（拒收额外费用单，责任方应付） */
    @Column(name = "bill_type", nullable = false)
    private String billType = "PURCHASE";

    /** 拒收产生的额外处理费用（元），默认 0 */
    @Column(name = "extra_fee", nullable = false)
    private BigDecimal extraFee = BigDecimal.ZERO;

    /** 额外费用承担方：MERCHANT / DRIVER_FLEET */
    @Column(name = "fee_payer")
    private String feePayer;

    /** 费用来源说明：什么费用、为何由该方承担，结算单原样展示，避免互相推责 */
    @Column(name = "fee_source")
    private String feeSource;

    /** 关联的拒收责任调查 */
    @Column(name = "investigation_id")
    private Long investigationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
