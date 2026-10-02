package com.wasteoil.reject;

import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.barrel.BarrelSealRecord;
import com.wasteoil.barrel.BarrelSealRecordRepository;
import com.wasteoil.barrel.BarrelStatus;
import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchService;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestService;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.settlement.SettlementService;
import com.wasteoil.track.TrackPoint;
import com.wasteoil.track.TrackPointRepository;
import com.wasteoil.weighing.PickupVerification;
import com.wasteoil.weighing.PickupVerificationRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拒收责任调查服务：
 * 拒收后追溯商户交接照片、司机封桶记录、运输轨迹、到厂检测结果并固化证据快照，
 * 给出责任建议；回收公司认定责任方与额外费用来源；
 * 需要返运的批次记录返运路线、临时存放点与二次处理结果，全部完成后联单闭环。
 */
@Service
public class RejectInvestigationService {

    private final RejectInvestigationRepository investigationRepository;
    private final RejectReturnRepository returnRepository;
    private final ManifestRepository manifestRepository;
    private final ManifestService manifestService;
    private final WeighingRecordRepository weighingRecordRepository;
    private final PickupVerificationRepository verificationRepository;
    private final BarrelSealRecordRepository sealRecordRepository;
    private final TrackPointRepository trackPointRepository;
    private final BarrelRepository barrelRepository;
    private final SettlementService settlementService;
    private final DispatchService dispatchService;
    private final ExceptionService exceptionService;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public RejectInvestigationService(RejectInvestigationRepository investigationRepository,
                                      RejectReturnRepository returnRepository,
                                      ManifestRepository manifestRepository,
                                      ManifestService manifestService,
                                      WeighingRecordRepository weighingRecordRepository,
                                      PickupVerificationRepository verificationRepository,
                                      BarrelSealRecordRepository sealRecordRepository,
                                      TrackPointRepository trackPointRepository,
                                      BarrelRepository barrelRepository,
                                      SettlementService settlementService,
                                      DispatchService dispatchService,
                                      ExceptionService exceptionService,
                                      EventPublisher eventPublisher,
                                      WasteOilProperties properties) {
        this.investigationRepository = investigationRepository;
        this.returnRepository = returnRepository;
        this.manifestRepository = manifestRepository;
        this.manifestService = manifestService;
        this.weighingRecordRepository = weighingRecordRepository;
        this.verificationRepository = verificationRepository;
        this.sealRecordRepository = sealRecordRepository;
        this.trackPointRepository = trackPointRepository;
        this.barrelRepository = barrelRepository;
        this.settlementService = settlementService;
        this.dispatchService = dispatchService;
        this.exceptionService = exceptionService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 处置厂拒收后自动发起调查（与收料同一事务）：
     * 聚合四类追溯证据并固化快照，运行责任分析器给出建议。
     */
    @Transactional
    public RejectInvestigation openInvestigation(Manifest manifest, PlantReceipt receipt) {
        Long dispatchId = manifest.getDispatchId();

        // 1. 商户交接照片（到店扫码核验留存，逗号分隔多个 URL）
        List<PickupVerification> verifications = verificationRepository.findByDispatchId(dispatchId);
        int handoverPhotoCount = verifications.stream()
                .mapToInt(v -> countPhotos(v.getPhotoUrls())).sum();

        // 2. 称重记录（本趟装车桶数 + 称重现场照片）
        List<WeighingRecord> weighings = weighingRecordRepository.findByManifestId(manifest.getId());
        int totalBarrelCount = weighings.size();
        int weighingPhotoCount = (int) weighings.stream()
                .filter(w -> w.getPhotoUrl() != null && !w.getPhotoUrl().isBlank()).count();

        // 3. 司机封桶记录
        List<BarrelSealRecord> seals = sealRecordRepository.findByDispatchIdOrderBySealedAtAsc(dispatchId);
        int sealedBarrelCount = seals.size();
        boolean allSealed = totalBarrelCount > 0 && sealedBarrelCount == totalBarrelCount;

        // 4. 运输轨迹与异常停留
        List<TrackPoint> tracks = trackPointRepository.findByDispatchIdOrderByRecordedAtAsc(dispatchId);
        List<RejectLiabilityAnalyzer.AbnormalStop> stops = RejectLiabilityAnalyzer.detectAbnormalStops(
                tracks, properties.getAbnormalStopMinutes(), properties.getAbnormalStopRadiusMeters());
        String stopDetail = stops.isEmpty() ? null
                : String.join("; ", stops.stream().map(RejectLiabilityAnalyzer.AbnormalStop::describe).toList());

        RejectLiabilityAnalyzer.EvidenceBundle evidence = new RejectLiabilityAnalyzer.EvidenceBundle(
                totalBarrelCount, sealedBarrelCount, allSealed, receipt.getSealIntact(),
                tracks.size(), stops.size(), handoverPhotoCount, weighingPhotoCount);
        RejectLiabilityAnalyzer.Verdict verdict = RejectLiabilityAnalyzer.analyze(evidence);

        RejectInvestigation inv = new RejectInvestigation();
        inv.setManifestId(manifest.getId());
        inv.setDispatchId(dispatchId);
        inv.setStatus("OPEN");
        inv.setHandoverPhotoCount(handoverPhotoCount);
        inv.setWeighingPhotoCount(weighingPhotoCount);
        inv.setSealedBarrelCount(sealedBarrelCount);
        inv.setTotalBarrelCount(totalBarrelCount);
        inv.setAllSealed(allSealed);
        inv.setSealIntactAtPlant(receipt.getSealIntact());
        inv.setPlantCheckDetail("质检 " + receipt.getQualityCheck()
                + "；拒收原因: " + receipt.getRejectReason()
                + (receipt.getSealNote() == null || receipt.getSealNote().isBlank()
                ? "" : "；封条说明: " + receipt.getSealNote()));
        inv.setTrackPointCount(tracks.size());
        inv.setAbnormalStopCount(stops.size());
        inv.setAbnormalStopDetail(stopDetail);
        inv.setContaminationNote(receipt.getRejectReason());
        inv.setSuggestedLiability(verdict.liability());
        inv.setSuggestedReason(verdict.reason());
        RejectInvestigation saved = investigationRepository.save(inv);

        Map<String, Object> payload = basePayload(saved);
        payload.put("suggestedLiability", verdict.liability().name());
        payload.put("suggestedReason", verdict.reason());
        eventPublisher.publish(KafkaTopics.REJECT_EVENTS,
                "manifest-" + manifest.getId(), "RejectInvestigationOpened", payload);
        return saved;
    }

    /**
     * 回收公司认定责任：确认责任方、额外处理费与费用来源，生成拒收费用结算单。
     *
     * @param noReturnRequired 无需返运（如厂内直接移交无害化处理），认定后联单直接闭环
     */
    @Transactional
    public RejectInvestigation confirm(Long manifestId, LiabilityParty party, BigDecimal extraFee,
                                       String feeSource, boolean noReturnRequired) {
        RejectInvestigation inv = getByManifestInternal(manifestId);
        if (!"OPEN".equals(inv.getStatus())) {
            throw BizException.conflict("责任调查已认定，不能重复认定");
        }
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        if (manifest.getStatus() != ManifestStatus.REJECTED) {
            throw BizException.conflict("联单状态为 " + manifest.getStatus() + "，不能认定拒收责任");
        }

        String payer = null;
        ExceptionType ticketType = null;
        if (party == LiabilityParty.MERCHANT) {
            payer = "MERCHANT";
            ticketType = ExceptionType.REJECT_LIABILITY_MERCHANT;
        } else if (party == LiabilityParty.DRIVER_FLEET) {
            payer = "DRIVER_FLEET";
            ticketType = ExceptionType.REJECT_LIABILITY_TRANSPORT;
        }
        if (payer != null) {
            if (extraFee == null || extraFee.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.badRequest("认定由商户或车队承担责任时，额外处理费用必须大于 0");
            }
        } else {
            extraFee = BigDecimal.ZERO;
        }

        String source = (feeSource == null || feeSource.isBlank())
                ? defaultFeeSource(manifest, inv, party, extraFee) : feeSource;

        inv.setLiabilityParty(party);
        inv.setExtraFee(extraFee);
        inv.setFeePayer(payer);
        inv.setFeeSource(source);
        inv.setNoReturnRequired(noReturnRequired);
        inv.setDecidedBy(com.wasteoil.common.AuthContext.accountId() == null ? "recycler"
                : com.wasteoil.common.AuthContext.accountId());
        inv.setDecidedAt(Instant.now());
        inv.setStatus("CONFIRMED");
        inv.setUpdatedAt(Instant.now());
        RejectInvestigation saved = investigationRepository.save(inv);

        // 结算单明确费用来源：拒收费用单（无收购款），谁的责任谁承担
        settlementService.createRejectFeeBill(manifest, payer, extraFee, source, saved.getId());

        if (ticketType != null) {
            exceptionService.raise(ticketType, "MANIFEST", manifestId,
                    "拒收责任认定: " + party + "；额外费用 " + extraFee + " 元；费用来源: " + source);
        }

        Map<String, Object> payload = basePayload(saved);
        payload.put("liabilityParty", party.name());
        payload.put("extraFee", extraFee);
        payload.put("feePayer", payer);
        payload.put("feeSource", source);
        payload.put("noReturnRequired", noReturnRequired);
        eventPublisher.publish(KafkaTopics.REJECT_EVENTS,
                "manifest-" + manifestId, "RejectLiabilityConfirmed", payload);

        if (noReturnRequired) {
            // 无需返运（厂内移交无害化处理）：责任与费用落地后直接闭环，油桶按已清空处置
            markBarrelsEmptied(manifestId);
            closeAfterReject(manifest);
        }
        return saved;
    }

    /** 拒收批次返运离厂：登记返运路线、车辆、司机 */
    @Transactional
    public RejectReturn registerReturn(Long manifestId, String returnRoute, Long vehicleId,
                                       Long driverId, String returnedBy) {
        RejectInvestigation inv = requireConfirmed(manifestId);
        if (inv.getNoReturnRequired()) {
            throw BizException.conflict("该拒收批次已认定无需返运");
        }
        if (returnRepository.findByManifestId(manifestId).isPresent()) {
            throw BizException.conflict("该拒收批次已登记返运，不能重复登记");
        }
        RejectReturn ret = new RejectReturn();
        ret.setManifestId(manifestId);
        ret.setInvestigationId(inv.getId());
        ret.setReturnRoute(returnRoute);
        ret.setVehicleId(vehicleId);
        ret.setDriverId(driverId);
        ret.setReturnedBy(returnedBy);
        ret.setDepartedPlantAt(Instant.now());
        ret.setStatus("DEPARTED");
        RejectReturn saved = returnRepository.save(ret);
        publishReturnEvent(manifestId, saved, "RejectReturnDeparted");
        return saved;
    }

    /** 返运批次到达临时存放点 */
    @Transactional
    public RejectReturn arriveStorage(Long manifestId, String storagePoint, String storagePhotoUrl) {
        RejectReturn ret = getReturnInternal(manifestId);
        if ("REPROCESSED".equals(ret.getStatus())) {
            throw BizException.conflict("该批次已完成二次处理");
        }
        ret.setStoragePoint(storagePoint);
        ret.setStorageArrivedAt(Instant.now());
        ret.setStoragePhotoUrl(storagePhotoUrl);
        ret.setStatus("IN_STORAGE");
        ret.setUpdatedAt(Instant.now());
        RejectReturn saved = returnRepository.save(ret);
        publishReturnEvent(manifestId, saved, "RejectReturnInStorage");
        return saved;
    }

    /** 登记二次处理结果，拒收批次闭环 */
    @Transactional
    public RejectReturn completeReprocess(Long manifestId, ReprocessResult result,
                                          BigDecimal reprocessWeightKg, String note) {
        RejectReturn ret = getReturnInternal(manifestId);
        ret.setReprocessResult(result);
        ret.setReprocessWeightKg(reprocessWeightKg);
        ret.setReprocessNote(note);
        ret.setReprocessedAt(Instant.now());
        ret.setStatus("REPROCESSED");
        ret.setUpdatedAt(Instant.now());
        RejectReturn saved = returnRepository.save(ret);

        // 油桶去向随二次处理结果更新，保证一桶一码可追溯
        updateBarrelsAfterReprocess(manifestId, result);

        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        closeAfterReject(manifest);

        publishReturnEvent(manifestId, saved, "RejectReturnReprocessed");
        return saved;
    }

    @Transactional(readOnly = true)
    public RejectInvestigation getByManifest(Long manifestId) {
        return getByManifestInternal(manifestId);
    }

    @Transactional(readOnly = true)
    public RejectReturn getReturn(Long manifestId) {
        return getReturnInternal(manifestId);
    }

    // ---------------- 内部方法 ----------------

    private void closeAfterReject(Manifest manifest) {
        // 拒收批次处置完毕（责任、费用、返运/二次处理落地），联单闭环，派单收尾
        manifestService.transition(manifest, ManifestStatus.CLOSED, "ManifestClosedAfterReject");
        dispatchService.complete(manifest.getDispatchId());
    }

    private void updateBarrelsAfterReprocess(Long manifestId, ReprocessResult result) {
        List<WeighingRecord> weighings = weighingRecordRepository.findByManifestId(manifestId);
        BarrelStatus target = switch (result) {
            case RE_ACCEPTED -> BarrelStatus.AT_PLANT;
            case DISPOSED -> BarrelStatus.EMPTIED;
            case RETURNED_MERCHANT -> BarrelStatus.AT_STORE;
        };
        for (WeighingRecord w : weighings) {
            Barrel barrel = barrelRepository.findById(w.getBarrelId()).orElseThrow();
            barrel.setStatus(target);
            barrel.setUpdatedAt(Instant.now());
            barrelRepository.save(barrel);
        }
    }

    /** 无需返运（厂内无害化处理）时，油桶按已清空处置 */
    private void markBarrelsEmptied(Long manifestId) {
        List<WeighingRecord> weighings = weighingRecordRepository.findByManifestId(manifestId);
        for (WeighingRecord w : weighings) {
            Barrel barrel = barrelRepository.findById(w.getBarrelId()).orElseThrow();
            barrel.setStatus(BarrelStatus.EMPTIED);
            barrel.setUpdatedAt(Instant.now());
            barrelRepository.save(barrel);
        }
    }

    private RejectInvestigation requireConfirmed(Long manifestId) {
        RejectInvestigation inv = getByManifestInternal(manifestId);
        if (!"CONFIRMED".equals(inv.getStatus())) {
            throw BizException.conflict("责任尚未认定，需先认定责任并明确费用来源后再安排返运");
        }
        return inv;
    }

    private RejectInvestigation getByManifestInternal(Long manifestId) {
        return investigationRepository.findByManifestId(manifestId)
                .orElseThrow(() -> BizException.notFound("拒收责任调查"));
    }

    private RejectReturn getReturnInternal(Long manifestId) {
        return returnRepository.findByManifestId(manifestId)
                .orElseThrow(() -> BizException.notFound("拒收返运记录"));
    }

    private int countPhotos(String photoUrls) {
        if (photoUrls == null || photoUrls.isBlank()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(photoUrls.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).count();
    }

    private String defaultFeeSource(Manifest manifest, RejectInvestigation inv,
                                    LiabilityParty party, BigDecimal extraFee) {
        String base = "联单 " + manifest.getManifestNo() + " 处置厂拒收（" + inv.getContaminationNote() + "）";
        if (party == LiabilityParty.MERCHANT) {
            return base + "：全部油桶起运前加封且到厂封条完好，判定商户源头混入厨余残渣，"
                    + "额外分拣/无害化处理费 " + extraFee + " 元由商户承担";
        }
        if (party == LiabilityParty.DRIVER_FLEET) {
            return base + "：到厂封条破损/缺失或运输途中存在异常停留，判定运输环节污染，"
                    + "额外处理费 " + extraFee + " 元由司机及所属车队承担";
        }
        return base + "：证据不足无法认定责任，暂不产生额外费用";
    }

    private Map<String, Object> basePayload(RejectInvestigation inv) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("investigationId", inv.getId());
        payload.put("manifestId", inv.getManifestId());
        payload.put("dispatchId", inv.getDispatchId());
        payload.put("status", inv.getStatus());
        return payload;
    }

    private void publishReturnEvent(Long manifestId, RejectReturn ret, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("returnId", ret.getId());
        payload.put("manifestId", manifestId);
        payload.put("investigationId", ret.getInvestigationId());
        payload.put("returnRoute", ret.getReturnRoute());
        payload.put("storagePoint", ret.getStoragePoint());
        payload.put("reprocessResult", ret.getReprocessResult() == null ? null : ret.getReprocessResult().name());
        payload.put("status", ret.getStatus());
        eventPublisher.publish(KafkaTopics.REJECT_EVENTS, "manifest-" + manifestId, type, payload);
    }
}
