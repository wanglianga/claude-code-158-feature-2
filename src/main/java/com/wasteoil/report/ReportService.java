package com.wasteoil.report;

import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.DispatchStatus;
import com.wasteoil.dispatch.Vehicle;
import com.wasteoil.dispatch.VehicleRepository;
import com.wasteoil.exception.ExceptionTicket;
import com.wasteoil.exception.ExceptionTicketRepository;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.settlement.Settlement;
import com.wasteoil.settlement.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 报表服务：监管趋势、车辆效率、商户回收证明。
 */
@Service
public class ReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ManifestRepository manifestRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final VehicleRepository vehicleRepository;
    private final ExceptionTicketRepository ticketRepository;
    private final SettlementRepository settlementRepository;

    public ReportService(ManifestRepository manifestRepository,
                         DispatchOrderRepository dispatchRepository,
                         VehicleRepository vehicleRepository,
                         ExceptionTicketRepository ticketRepository,
                         SettlementRepository settlementRepository) {
        this.manifestRepository = manifestRepository;
        this.dispatchRepository = dispatchRepository;
        this.vehicleRepository = vehicleRepository;
        this.ticketRepository = ticketRepository;
        this.settlementRepository = settlementRepository;
    }

    /**
     * 监管趋势：按天聚合合规接收量、联单量、异常量，观察废油是否流向合规处置。
     */
    @Transactional(readOnly = true)
    public List<DailyTrend> regulatorTrend(Instant from, Instant to) {
        List<Manifest> manifests = manifestRepository.findByCreatedAtBetween(from, to);
        Map<LocalDate, List<Manifest>> byDay = manifests.stream()
                .collect(Collectors.groupingBy(m -> LocalDate.ofInstant(m.getCreatedAt(), ZONE),
                        TreeMap::new, Collectors.toList()));
        List<ExceptionTicket> tickets = ticketRepository.findAll();

        List<DailyTrend> trend = new ArrayList<>();
        byDay.forEach((day, list) -> {
            BigDecimal received = list.stream()
                    .map(m -> m.getArrivalWeightKg() == null ? BigDecimal.ZERO : m.getArrivalWeightKg())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long closed = list.stream().filter(m -> m.getStatus() == ManifestStatus.CLOSED).count();
            long exception = list.stream().filter(m -> m.getStatus() == ManifestStatus.EXCEPTION
                    || m.getStatus() == ManifestStatus.REJECTED).count();
            long ticketCount = tickets.stream()
                    .filter(t -> LocalDate.ofInstant(t.getCreatedAt(), ZONE).equals(day))
                    .count();
            trend.add(new DailyTrend(day, list.size(), received, closed, exception, ticketCount));
        });
        return trend;
    }

    /**
     * 车辆效率：趟次、总运量、平均载重、迟到次数。
     */
    @Transactional(readOnly = true)
    public List<VehicleEfficiency> vehicleEfficiency() {
        List<ExceptionTicket> lateTickets = ticketRepository.findAll().stream()
                .filter(t -> t.getType() == ExceptionType.DRIVER_LATE)
                .toList();
        List<VehicleEfficiency> result = new ArrayList<>();
        for (Vehicle vehicle : vehicleRepository.findAll()) {
            List<DispatchOrder> dispatches = dispatchRepository.findByVehicleId(vehicle.getId());
            long completed = dispatches.stream()
                    .filter(d -> d.getStatus() == DispatchStatus.COMPLETED).count();
            BigDecimal totalKg = BigDecimal.ZERO;
            for (DispatchOrder d : dispatches) {
                if (d.getStatus() == DispatchStatus.COMPLETED) {
                    Optional<Manifest> m = manifestRepository.findByDispatchId(d.getId());
                    if (m.isPresent() && m.get().getArrivalWeightKg() != null) {
                        totalKg = totalKg.add(m.get().getArrivalWeightKg());
                    }
                }
            }
            long lateCount = dispatches.stream()
                    .filter(d -> lateTickets.stream().anyMatch(
                            t -> "DISPATCH".equals(t.getRefType()) && t.getRefId().equals(d.getId())))
                    .count();
            BigDecimal avgKg = completed > 0
                    ? totalKg.divide(BigDecimal.valueOf(completed), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            result.add(new VehicleEfficiency(vehicle.getId(), vehicle.getPlateNo(),
                    vehicle.getCapacityKg(), dispatches.size(), completed,
                    totalKg, avgKg, lateCount));
        }
        return result;
    }

    /**
     * 商户回收证明：期间内的合规联单清单与合计，作为回收凭证与结算依据。
     * 拒收批次（结算单为 REJECT_FEE）不属于合规收购，即使返运二次处理后联单闭环，
     * 也不计入回收证明的重量与金额。
     */
    @Transactional(readOnly = true)
    public RecyclingCertificate certificate(Long storeId, Instant from, Instant to) {
        List<Manifest> manifests = manifestRepository
                .findByStoreIdAndCreatedAtBetween(storeId, from, to).stream()
                .filter(m -> m.getStatus() == ManifestStatus.CLOSED
                        || m.getStatus() == ManifestStatus.VERIFIED
                        || m.getStatus() == ManifestStatus.DISPUTED)
                .toList();
        BigDecimal totalWeight = BigDecimal.ZERO;
        int totalBarrels = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<CertificateItem> items = new ArrayList<>();
        for (Manifest m : manifests) {
            Settlement s = settlementRepository.findByManifestId(m.getId()).orElse(null);
            if (s != null && "REJECT_FEE".equals(s.getBillType())) {
                continue; // 拒收费用单：废油未按收购流程合规接收，不出具回收量
            }
            BigDecimal weight = m.getArrivalWeightKg() == null
                    ? BigDecimal.ZERO : m.getArrivalWeightKg();
            totalWeight = totalWeight.add(weight);
            if (m.getBarrelCountArrival() != null) {
                totalBarrels += m.getBarrelCountArrival();
            }
            BigDecimal amount = s == null ? BigDecimal.ZERO : s.getAmount();
            totalAmount = totalAmount.add(amount);
            items.add(new CertificateItem(m.getManifestNo(), m.getStatus().name(),
                    m.getArrivalWeightKg(), m.getBarrelCountArrival(),
                    m.getComplianceResult(), amount));
        }
        return new RecyclingCertificate(storeId, from, to, items.size(),
                totalBarrels, totalWeight, totalAmount, items);
    }

    public record DailyTrend(LocalDate date, int manifestCount, BigDecimal receivedKg,
                             long closedCount, long exceptionCount, long ticketCount) {
    }

    public record VehicleEfficiency(Long vehicleId, String plateNo, BigDecimal capacityKg,
                                    int dispatchCount, long completedCount,
                                    BigDecimal totalKg, BigDecimal avgKgPerTrip, long lateCount) {
    }

    public record CertificateItem(String manifestNo, String status, BigDecimal weightKg,
                                  Integer barrelCount, String complianceResult, BigDecimal amount) {
    }

    public record RecyclingCertificate(Long storeId, Instant from, Instant to,
                                       int manifestCount, int totalBarrels,
                                       BigDecimal totalWeightKg, BigDecimal totalAmount,
                                       List<CertificateItem> items) {
    }
}
