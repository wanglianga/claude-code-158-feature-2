package com.wasteoil.barrel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 司机封桶记录：装车称重后逐桶加封一次性封条并拍照留证。
 * 封条是区分"商户源头混入"与"运输途中污染"的关键证据：
 * 到厂封条完好则运输途中无法开封，污染只能来自门店源头；
 * 封条破损/缺失则运输环节（司机/车队）承担责任。
 */
@Getter
@Setter
@Entity
@Table(name = "barrel_seal_records")
public class BarrelSealRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    @Column(name = "barrel_id", nullable = false)
    private Long barrelId;

    /** 一次性封条编号 */
    @Column(name = "seal_code", nullable = false)
    private String sealCode;

    /** 封桶司机 */
    @Column(name = "sealed_by", nullable = false)
    private String sealedBy;

    /** 封桶现场照片 */
    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "note")
    private String note;

    @Column(name = "sealed_at", nullable = false)
    private Instant sealedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
