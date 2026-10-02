package com.wasteoil.settlement;

import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.manifest.Manifest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 结算服务：以电子联单为唯一结算依据。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementFeeRepository settlementFeeRepository;
    private final WasteOilProperties properties;

    public SettlementService(SettlementRepository settlementRepository,
                             SettlementFeeRepository settlementFeeRepository,
                             WasteOilProperties properties) {
        this.settlementRepository = settlementRepository;
        this.settlementFeeRepository = settlementFeeRepository;
        this.properties = properties;
    }

    /** 联单合规通过后按到厂重量生成结算单 */
    @Transactional(propagation = Propagation.REQUIRED)
    public Settlement createFor(Manifest manifest) {
        return settlementRepository.findByManifestId(manifest.getId()).orElseGet(() -> {
            BigDecimal weight = manifest.getArrivalWeightKg() == null
                    ? BigDecimal.ZERO : manifest.getArrivalWeightKg();
            Settlement settlement = new Settlement();
            settlement.setManifestId(manifest.getId());
            settlement.setStoreId(manifest.getStoreId());
            settlement.setWeightKg(weight);
            settlement.setUnitPrice(properties.getUnitPricePerKg());
            settlement.setAmount(weight.multiply(properties.getUnitPricePerKg())
                    .setScale(2, RoundingMode.HALF_UP));
            settlement.setStatus("CONFIRMED");
            return settlementRepository.save(settlement);
        });
    }

    /** 商户质疑时冻结结算 */
    @Transactional
    public Settlement freeze(Long manifestId) {
        Settlement settlement = getByManifest(manifestId);
        settlement.setStatus("FROZEN");
        settlement.setUpdatedAt(Instant.now());
        return settlementRepository.save(settlement);
    }

    /** 争议解决：按协商重量调整结算并恢复确认 */
    @Transactional
    public Settlement adjustAndConfirm(Long manifestId, BigDecimal adjustedWeightKg) {
        Settlement settlement = getByManifest(manifestId);
        if (adjustedWeightKg != null) {
            settlement.setWeightKg(adjustedWeightKg);
            settlement.setAmount(adjustedWeightKg.multiply(settlement.getUnitPrice())
                    .setScale(2, RoundingMode.HALF_UP));
        }
        settlement.setStatus("CONFIRMED");
        settlement.setUpdatedAt(Instant.now());
        return settlementRepository.save(settlement);
    }

    @Transactional(readOnly = true)
    public Settlement getByManifest(Long manifestId) {
        return settlementRepository.findByManifestId(manifestId)
                .orElseThrow(() -> BizException.notFound("结算单"));
    }

    @Transactional(readOnly = true)
    public List<Settlement> listByStore(Long storeId) {
        return settlementRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
    }

    /** 门店的额外费用单（费用来源留痕） */
    @Transactional(readOnly = true)
    public List<SettlementFee> listFeesByStore(Long storeId) {
        return settlementFeeRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
    }

    /** 联单的额外费用单 */
    @Transactional(readOnly = true)
    public List<SettlementFee> listFeesByManifest(Long manifestId) {
        return settlementFeeRepository.findByManifestId(manifestId);
    }
}
