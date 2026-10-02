package com.wasteoil.plant;

import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.barrel.BarrelStatus;
import com.wasteoil.common.BizException;
import com.wasteoil.compliance.ComplianceService;
import com.wasteoil.dispatch.DispatchService;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.reject.RejectInvestigationService;
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
 * 处置厂收料服务：二次称重、质检、接收/拒收。
 */
@Service
public class PlantService {

    private final PlantReceiptRepository receiptRepository;
    private final ManifestRepository manifestRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final BarrelRepository barrelRepository;
    private final ComplianceService complianceService;
    private final DispatchService dispatchService;
    private final ExceptionService exceptionService;
    private final RejectInvestigationService rejectInvestigationService;
    private final EventPublisher eventPublisher;

    public PlantService(PlantReceiptRepository receiptRepository,
                        ManifestRepository manifestRepository,
                        WeighingRecordRepository weighingRecordRepository,
                        BarrelRepository barrelRepository,
                        ComplianceService complianceService,
                        DispatchService dispatchService,
                        ExceptionService exceptionService,
                        RejectInvestigationService rejectInvestigationService,
                        EventPublisher eventPublisher) {
        this.receiptRepository = receiptRepository;
        this.manifestRepository = manifestRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.barrelRepository = barrelRepository;
        this.complianceService = complianceService;
        this.dispatchService = dispatchService;
        this.exceptionService = exceptionService;
        this.rejectInvestigationService = rejectInvestigationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处置厂收料：二次称重 + 质检 + 接收/拒收决定。
     * 接收后自动执行合规闭环比对；拒收时必须核验封条是否完好，系统自动发起责任调查。
     */
    @Transactional
    public PlantReceipt receive(Long manifestId, BigDecimal arrivalWeightKg, Integer barrelCount,
                                String qualityCheck, String decision,
                                String rejectReason, Boolean sealIntact, String sealNote,
                                String receivedBy) {
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        if (manifest.getStatus() != ManifestStatus.IN_TRANSIT) {
            throw BizException.conflict("联单状态为 " + manifest.getStatus() + "，不能收料");
        }
        if (receiptRepository.findByManifestId(manifestId).isPresent()) {
            throw BizException.conflict("该联单已完成收料，不能重复");
        }
        boolean reject = "REJECT".equalsIgnoreCase(decision);
        if (reject && (rejectReason == null || rejectReason.isBlank())) {
            throw BizException.badRequest("拒收必须填写原因");
        }
        if (reject && sealIntact == null) {
            throw BizException.badRequest("拒收必须核验并填写到厂封条是否完好（sealIntact）");
        }

        PlantReceipt receipt = new PlantReceipt();
        receipt.setManifestId(manifestId);
        receipt.setArrivalWeightKg(arrivalWeightKg);
        receipt.setBarrelCount(barrelCount);
        receipt.setQualityCheck(qualityCheck);
        receipt.setDecision(reject ? "REJECT" : "ACCEPT");
        receipt.setRejectReason(rejectReason);
        receipt.setSealIntact(sealIntact);
        receipt.setSealNote(sealNote);
        receipt.setReceivedBy(receivedBy);
        PlantReceipt saved = receiptRepository.save(receipt);

        if (reject) {
            // 拒收：联单进入 REJECTED，登记异常并自动发起责任调查（追溯照片/封桶/轨迹/检测）
            manifest.setStatus(ManifestStatus.REJECTED);
            manifest.setArrivalWeightKg(arrivalWeightKg);
            manifest.setBarrelCountArrival(barrelCount);
            manifest.setQualityIssue(true);
            manifest.setExceptionNote("处置厂拒收: " + rejectReason);
            manifest.setUpdatedAt(Instant.now());
            manifestRepository.save(manifest);
            exceptionService.raise(ExceptionType.PLANT_REJECT, "MANIFEST", manifestId,
                    "处置厂拒收，原因: " + rejectReason);
            rejectInvestigationService.openInvestigation(manifest, saved);
        } else {
            // 接收：油桶到厂，执行合规闭环比对
            markBarrelsAtPlant(manifest);
            complianceService.evaluate(manifest, saved);
            // 运输工作完成
            dispatchService.complete(manifest.getDispatchId());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("receiptId", saved.getId());
        payload.put("manifestId", manifestId);
        payload.put("arrivalWeightKg", arrivalWeightKg);
        payload.put("barrelCount", barrelCount);
        payload.put("qualityCheck", qualityCheck);
        payload.put("decision", saved.getDecision());
        eventPublisher.publish(KafkaTopics.MANIFEST_EVENTS,
                "manifest-" + manifestId, "PlantReceiptRecorded", payload);
        return saved;
    }

    private void markBarrelsAtPlant(Manifest manifest) {
        List<WeighingRecord> records = weighingRecordRepository.findByManifestId(manifest.getId());
        for (WeighingRecord record : records) {
            Barrel barrel = barrelRepository.findById(record.getBarrelId()).orElseThrow();
            barrel.setStatus(BarrelStatus.AT_PLANT);
            barrel.setUpdatedAt(Instant.now());
            barrelRepository.save(barrel);
        }
    }

    @Transactional(readOnly = true)
    public PlantReceipt getByManifest(Long manifestId) {
        return receiptRepository.findByManifestId(manifestId)
                .orElseThrow(() -> BizException.notFound("收料记录"));
    }
}
