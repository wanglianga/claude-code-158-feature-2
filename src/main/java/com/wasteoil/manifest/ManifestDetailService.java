package com.wasteoil.manifest;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.appointment.AppointmentRepository;
import com.wasteoil.common.BizException;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.exception.ExceptionTicket;
import com.wasteoil.exception.ExceptionTicketRepository;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.plant.PlantReceiptRepository;
import com.wasteoil.rejection.RejectionCase;
import com.wasteoil.rejection.RejectionCaseRepository;
import com.wasteoil.settlement.Settlement;
import com.wasteoil.settlement.SettlementFee;
import com.wasteoil.settlement.SettlementFeeRepository;
import com.wasteoil.settlement.SettlementRepository;
import com.wasteoil.track.TrackPoint;
import com.wasteoil.track.TrackPointRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 联单详情组装：一次查询返回全链路数据，
 * 司机、商户、处置厂、监管端看到的都是这同一份口径。
 */
@Service
public class ManifestDetailService {

    private final ManifestRepository manifestRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final AppointmentRepository appointmentRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final TrackPointRepository trackPointRepository;
    private final PlantReceiptRepository receiptRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementFeeRepository settlementFeeRepository;
    private final RejectionCaseRepository rejectionCaseRepository;
    private final ExceptionTicketRepository ticketRepository;

    public ManifestDetailService(ManifestRepository manifestRepository,
                                 DispatchOrderRepository dispatchRepository,
                                 AppointmentRepository appointmentRepository,
                                 WeighingRecordRepository weighingRecordRepository,
                                 TrackPointRepository trackPointRepository,
                                 PlantReceiptRepository receiptRepository,
                                 SettlementRepository settlementRepository,
                                 SettlementFeeRepository settlementFeeRepository,
                                 RejectionCaseRepository rejectionCaseRepository,
                                 ExceptionTicketRepository ticketRepository) {
        this.manifestRepository = manifestRepository;
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.trackPointRepository = trackPointRepository;
        this.receiptRepository = receiptRepository;
        this.settlementRepository = settlementRepository;
        this.settlementFeeRepository = settlementFeeRepository;
        this.rejectionCaseRepository = rejectionCaseRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional(readOnly = true)
    public ManifestDetail detail(Long manifestId) {
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        DispatchOrder dispatch = dispatchRepository.findById(manifest.getDispatchId()).orElse(null);
        Appointment appointment = dispatch == null ? null
                : appointmentRepository.findById(dispatch.getAppointmentId()).orElse(null);
        List<WeighingRecord> weighings = weighingRecordRepository.findByManifestId(manifestId);
        List<TrackPoint> tracks = dispatch == null ? List.of()
                : trackPointRepository.findByDispatchIdOrderByRecordedAtAsc(dispatch.getId());
        PlantReceipt receipt = receiptRepository.findByManifestId(manifestId).orElse(null);
        Settlement settlement = settlementRepository.findByManifestId(manifestId).orElse(null);
        List<SettlementFee> settlementFees = settlementFeeRepository.findByManifestId(manifestId);
        RejectionCase rejectionCase = rejectionCaseRepository.findByManifestId(manifestId).orElse(null);
        List<ExceptionTicket> exceptions = ticketRepository.findByRefTypeAndRefId("MANIFEST", manifestId);
        return new ManifestDetail(manifest, appointment, dispatch, weighings, tracks,
                receipt, settlement, settlementFees, rejectionCase, exceptions);
    }

    /**
     * 联单全链路详情：预约、派单、称重、轨迹、收料、结算、额外费用、拒收认定、异常，
     * 一份数据各方共用。
     */
    public record ManifestDetail(
            Manifest manifest,
            Appointment appointment,
            DispatchOrder dispatch,
            List<WeighingRecord> weighings,
            List<TrackPoint> tracks,
            PlantReceipt receipt,
            Settlement settlement,
            List<SettlementFee> settlementFees,
            RejectionCase rejectionCase,
            List<ExceptionTicket> exceptions) {
    }
}
