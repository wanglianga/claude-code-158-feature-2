package com.wasteoil.dispatch;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 司机封桶记录：装车称重后逐桶加封，留存封条编号与照片。
 * 处置厂拒收定责时作为关键证据——封条完好则污染源于商户装车前，
 * 封条破损或未加封则运输环节无法自证清白。
 */
@Getter
@Setter
@Entity
@Table(name = "barrel_seals")
public class BarrelSeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    @Column(name = "barrel_id", nullable = false)
    private Long barrelId;

    /** 封条编号 */
    @Column(name = "seal_no", nullable = false)
    private String sealNo;

    /** 加封照片 */
    @Column(name = "photo_url")
    private String photoUrl;

    /** 加封人（司机） */
    @Column(name = "sealed_by", nullable = false)
    private String sealedBy;

    @Column(name = "sealed_at", nullable = false)
    private Instant sealedAt = Instant.now();
}
