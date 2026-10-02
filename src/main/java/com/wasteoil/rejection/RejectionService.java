package com.wasteoil.rejection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.dispatch.BarrelSeal;
import com.wasteoil.dispatch.BarrelSealRepository;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionTicket;
import com.wasteoil.exception.ExceptionTicketRepository;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.settlement.SettlementFee;
import com.wasteoil.settlement.SettlementFeeRepository;
import com.wasteoil.track.TrackPointRepository;
import com.wasteoil.weighing.PickupVerification;
import com.wasteoil.weighing.PickupVerificationRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拒收责任认定服务：处置厂拒收后自动建案并固化证据快照，
 * 依据证据链给出责任建议，回收公司定责后生成费用来源明确的结算费用单，
 * 并跟踪返运路线、临时存放点与二次处理结果。
 */
@Slf4j
@Service
public class RejectionService {

    private final RejectionCaseRepository caseRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final BarrelRepository barrelRepository;
    private final BarrelSealRepository barrelSealRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final PickupVerificationRepository verificationRepository;
    private final TrackPointRepository trackPointRepository;
    private final SettlementFeeRepository settlementFeeRepository;
    private final ExceptionService exceptionService;
    private final ExceptionTicketRepository ticketRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public RejectionService(RejectionCaseRepository caseRepository,
                            DispatchOrderRepository dispatchRepository,
                            BarrelRepository barrelRepository,
                            BarrelSealRepository barrelSealRepository,
                            WeighingRecordRepository weighingRecordRepository,
                            PickupVerificationRepository verificationRepository,
                            TrackPointRepository trackPointRepository,
                            SettlementFeeRepository settlementFeeRepository,
                            ExceptionService exceptionService,
                            ExceptionTicketRepository ticketRepository,
                            EventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.dispatchRepository = dispatchRepository;
        this.barrelRepository = barrelRepository;
        this.barrelSealRepository = barrelSealRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.verificationRepository = verificationRepository;
        this.trackPointRepository = trackPointRepository;
        this.settlementFeeRepository = settlementFeeRepository;
        this.exceptionService = exceptionService;
        this.ticketRepository = ticketRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 处置厂拒收时自动建案（与收料同一事务）：
     * 追溯商户交接照片、司机封桶记录、运输轨迹与到厂检测结果，固化证据快照。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RejectionCase createFor(Manifest manifest, PlantReceipt receipt) {
        Long dispatchId = manifest.getDispatchId();
        List<WeighingRecord> weighings = weighingRecordRepository.findByManifestId(manifest.getId());
        List<BarrelSeal> seals = barrelSealRepository.findByDispatchId(dispatchId);
        List<PickupVerification> verifications = verificationRepository.findByDispatchId(dispatchId);
        long trackCount = trackPointRepository.countByDispatchId(dispatchId);
        boolean trackMissing = Boolean.TRUE.equals(manifest.getTrackMissing()) || trackCount == 0;

        // 商户交接照片：扫码核验现场照片 + 逐桶称重照片
        List<String> handoverPhotos = new ArrayList<>();
        for (PickupVerification v : verifications) {
            if (v.getPhotoUrls() != null && !v.getPhotoUrls().isBlank()) {
                handoverPhotos.addAll(List.of(v.getPhotoUrls().split(",")));
            }
        }
        String handoverName = verifications.isEmpty() ? null : verifications.get(0).getHandoverName();
        for (WeighingRecord w : weighings) {
            if (w.getPhotoUrl() != null && !w.getPhotoUrl().isBlank()) {
                handoverPhotos.add(w.getPhotoUrl());
            }
        }

        // 司机封桶记录（带桶码，便于对照）
        List<Map<String, Object>> sealRecords = new ArrayList<>();
        for (BarrelSeal s : seals) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("barrelCode", barrelRepository.findById(s.getBarrelId())
                    .map(Barrel::getCode).orElse(String.valueOf(s.getBarrelId())));
            item.put("sealNo", s.getSealNo());
            item.put("sealedBy", s.getSealedBy());
            item.put("sealedAt", s.getSealedAt().toString());
            item.put("photoUrl", s.getPhotoUrl());
            sealRecords.add(item);
        }

        String suggested = LiabilityAnalyzer.suggest(receipt.getQualityCheck(), receipt.getSealCheck(),
                weighings.size(), seals.size(), trackMissing);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("handoverName", handoverName);
        evidence.put("handoverPhotos", handoverPhotos);
        evidence.put("sealRecords", sealRecords);
        evidence.put("weighedBarrelCount", weighings.size());
        evidence.put("sealedBarrelCount", seals.size());
        evidence.put("trackPointCount", trackCount);
        evidence.put("trackMissing", trackMissing);
        evidence.put("arrivalQualityCheck", receipt.getQualityCheck());
        evidence.put("arrivalSealCheck", receipt.getSealCheck());
        evidence.put("arrivalWeightKg", receipt.getArrivalWeightKg());
        evidence.put("rejectReason", receipt.getRejectReason());

        RejectionCase c = new RejectionCase();
        c.setManifestId(manifest.getId());
        c.setReceiptId(receipt.getId());
        c.setDispatchId(dispatchId);
        c.setStoreId(manifest.getStoreId());
        c.setStatus(RejectionCase.STATUS_PENDING);
        c.setEvidenceJson(writeJson(evidence));
        c.setSuggestedLiability(suggested);
        RejectionCase saved = caseRepository.save(c);

        publish(saved, "RejectionCaseCreated",
                Map.of("suggestedLiability", suggested, "rejectReason",
                        receipt.getRejectReason() == null ? "" : receipt.getRejectReason()));
        return saved;
    }

    /**
     * 回收公司定责：划分商户源头混入 / 运输中污染。
     * 费用承担方由责任方推导（商户源头→商户，运输污染→司机与车队），
     * 同时生成费用来源明确的结算费用单。
     */
    @Transactional
    public RejectionCase determine(Long caseId, String liabilityParty,
                                   String note, BigDecimal extraFeeAmount) {
        RejectionCase c = get(caseId);
        if (!RejectionCase.STATUS_PENDING.equals(c.getStatus())) {
            throw BizException.conflict("案件状态为 " + c.getStatus() + "，不能定责");
        }
        if (!RejectionCase.PARTY_MERCHANT.equals(liabilityParty)
                && !RejectionCase.PARTY_TRANSPORT.equals(liabilityParty)) {
            throw BizException.badRequest("责任方只能是 MERCHANT_SOURCE（商户源头混入）或 TRANSPORT（运输中污染）");
        }
        if (note == null || note.isBlank()) {
            throw BizException.badRequest("定责必须填写说明，作为各方共同依据");
        }
        if (extraFeeAmount != null && extraFeeAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("额外处理费不能为负");
        }

        // 责任方 → 费用承担方：商户源头混入→商户；运输中污染→司机与车队
        String bearer = RejectionCase.PARTY_MERCHANT.equals(liabilityParty)
                ? SettlementFee.BEARER_MERCHANT : SettlementFee.BEARER_FLEET;
        c.setLiabilityParty(liabilityParty);
        c.setDeterminationNote(note);
        c.setFeeBearer(bearer);
        c.setDeterminedBy(AuthContext.accountId() == null ? "recycler" : AuthContext.accountId());
        c.setDeterminedAt(Instant.now());
        c.setStatus(RejectionCase.STATUS_DETERMINED);

        if (extraFeeAmount != null && extraFeeAmount.compareTo(BigDecimal.ZERO) > 0) {
            c.setExtraFeeAmount(extraFeeAmount);
            createFee(c, extraFeeAmount, bearer);
        }
        c.setUpdatedAt(Instant.now());
        RejectionCase saved = caseRepository.save(c);

        publish(saved, "RejectionLiabilityDetermined", Map.of(
                "liabilityParty", liabilityParty,
                "feeBearer", bearer,
                "extraFeeAmount", extraFeeAmount == null ? BigDecimal.ZERO : extraFeeAmount));
        return saved;
    }

    /** 生成结算费用单：费用来源写明拒收案件与责任划分，避免互相推责 */
    private void createFee(RejectionCase c, BigDecimal amount, String bearer) {
        DispatchOrder dispatch = dispatchRepository.findById(c.getDispatchId()).orElseThrow();
        SettlementFee fee = new SettlementFee();
        fee.setManifestId(c.getManifestId());
        fee.setStoreId(c.getStoreId());
        fee.setRejectionCaseId(c.getId());
        fee.setFeeType(SettlementFee.TYPE_EXTRA_PROCESSING);
        fee.setAmount(amount);
        fee.setBearer(bearer);
        if (SettlementFee.BEARER_FLEET.equals(bearer)) {
            fee.setBearerDriverId(dispatch.getDriverId());
            fee.setBearerVehicleId(dispatch.getVehicleId());
        }
        fee.setSourceNote(SettlementFee.BEARER_MERCHANT.equals(bearer)
                ? "处置厂拒收额外处理费（商户源头混入厨余，由商户承担）"
                : "处置厂拒收额外处理费（运输途中污染，由司机与车队承担）");
        settlementFeeRepository.save(fee);
    }

    /** 安排返运：记录返运路线与临时存放点 */
    @Transactional
    public RejectionCase arrangeReturn(Long caseId, String returnRoute, String tempStoragePoint) {
        RejectionCase c = get(caseId);
        if (!RejectionCase.STATUS_DETERMINED.equals(c.getStatus())) {
            throw BizException.conflict("案件状态为 " + c.getStatus() + "，不能安排返运");
        }
        if (returnRoute == null || returnRoute.isBlank()) {
            throw BizException.badRequest("返运路线不能为空");
        }
        if (tempStoragePoint == null || tempStoragePoint.isBlank()) {
            throw BizException.badRequest("临时存放点不能为空");
        }
        c.setReturnRoute(returnRoute);
        c.setTempStoragePoint(tempStoragePoint);
        c.setReturnArrangedAt(Instant.now());
        c.setStatus(RejectionCase.STATUS_RETURNING);
        c.setUpdatedAt(Instant.now());
        RejectionCase saved = caseRepository.save(c);
        publish(saved, "RejectionReturnArranged",
                Map.of("returnRoute", returnRoute, "tempStoragePoint", tempStoragePoint));
        return saved;
    }

    /** 登记二次处理结果 */
    @Transactional
    public RejectionCase recordReprocessing(Long caseId, String secondaryResult) {
        RejectionCase c = get(caseId);
        if (!RejectionCase.STATUS_RETURNING.equals(c.getStatus())) {
            throw BizException.conflict("案件状态为 " + c.getStatus() + "，不能登记二次处理结果");
        }
        if (secondaryResult == null || secondaryResult.isBlank()) {
            throw BizException.badRequest("二次处理结果不能为空");
        }
        c.setSecondaryResult(secondaryResult);
        c.setReprocessedAt(Instant.now());
        c.setStatus(RejectionCase.STATUS_REPROCESSED);
        c.setUpdatedAt(Instant.now());
        RejectionCase saved = caseRepository.save(c);
        publish(saved, "RejectionReprocessed", Map.of("secondaryResult", secondaryResult));
        return saved;
    }

    /** 结案：定责后无需返运，或二次处理完成后结案；联动处理拒收异常工单 */
    @Transactional
    public RejectionCase close(Long caseId) {
        RejectionCase c = get(caseId);
        if (!RejectionCase.STATUS_DETERMINED.equals(c.getStatus())
                && !RejectionCase.STATUS_REPROCESSED.equals(c.getStatus())) {
            throw BizException.conflict("案件状态为 " + c.getStatus() + "，不能结案");
        }
        c.setStatus(RejectionCase.STATUS_CLOSED);
        c.setUpdatedAt(Instant.now());
        RejectionCase saved = caseRepository.save(c);

        // 联动处理该联单的拒收异常工单，形成闭环
        for (ExceptionTicket ticket : ticketRepository.findByRefTypeAndRefId("MANIFEST", c.getManifestId())) {
            if (ticket.getType() == ExceptionType.PLANT_REJECT && "OPEN".equals(ticket.getStatus())) {
                exceptionService.resolve(ticket.getId(),
                        "拒收责任认定结案（案件 #" + c.getId() + "）");
            }
        }
        publish(saved, "RejectionClosed", Map.of());
        return saved;
    }

    @Transactional(readOnly = true)
    public RejectionCase get(Long id) {
        return caseRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("拒收责任认定单"));
    }

    @Transactional(readOnly = true)
    public RejectionCase getByManifest(Long manifestId) {
        return caseRepository.findByManifestId(manifestId)
                .orElseThrow(() -> BizException.notFound("拒收责任认定单"));
    }

    @Transactional(readOnly = true)
    public List<RejectionCase> list(String status) {
        if (status == null || status.isBlank()) {
            return caseRepository.findAll();
        }
        return caseRepository.findByStatus(status.toUpperCase());
    }

    /** 解析证据快照为结构化数据，供各端查看同一口径 */
    public Map<String, Object> parseEvidence(RejectionCase c) {
        try {
            return objectMapper.readValue(c.getEvidenceJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("证据快照解析失败 caseId={}", c.getId(), e);
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("证据快照序列化失败", e);
        }
    }

    private void publish(RejectionCase c, String type, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("caseId", c.getId());
        payload.put("manifestId", c.getManifestId());
        payload.put("storeId", c.getStoreId());
        payload.put("status", c.getStatus());
        eventPublisher.publish(KafkaTopics.REJECTION_EVENTS,
                "manifest-" + c.getManifestId(), type, payload);
    }
}
