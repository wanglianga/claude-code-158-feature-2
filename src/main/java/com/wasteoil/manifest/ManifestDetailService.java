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
import com.wasteoil.reject.RejectInvestigation;
import com.wasteoil.reject.RejectInvestigationRepository;
import com.wasteoil.reject.RejectReturn;
import com.wasteoil.reject.RejectReturnRepository;
import com.wasteoil.settlement.Settlement;
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
    private final ExceptionTicketRepository ticketRepository;
    private final RejectInvestigationRepository investigationRepository;
    private final RejectReturnRepository rejectReturnRepository;

    public ManifestDetailService(ManifestRepository manifestRepository,
                                 DispatchOrderRepository dispatchRepository,
                                 AppointmentRepository appointmentRepository,
                                 WeighingRecordRepository weighingRecordRepository,
                                 TrackPointRepository trackPointRepository,
                                 PlantReceiptRepository receiptRepository,
                                 SettlementRepository settlementRepository,
                                 ExceptionTicketRepository ticketRepository,
                                 RejectInvestigationRepository investigationRepository,
                                 RejectReturnRepository rejectReturnRepository) {
        this.manifestRepository = manifestRepository;
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.trackPointRepository = trackPointRepository;
        this.receiptRepository = receiptRepository;
        this.settlementRepository = settlementRepository;
        this.ticketRepository = ticketRepository;
        this.investigationRepository = investigationRepository;
        this.rejectReturnRepository = rejectReturnRepository;
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
        List<ExceptionTicket> exceptions = ticketRepository.findByRefTypeAndRefId("MANIFEST", manifestId);
        RejectInvestigation investigation =
                investigationRepository.findByManifestId(manifestId).orElse(null);
        RejectReturn rejectReturn = rejectReturnRepository.findByManifestId(manifestId).orElse(null);
        return new ManifestDetail(manifest, appointment, dispatch, weighings, tracks,
                receipt, settlement, exceptions, investigation, rejectReturn);
    }

    /**
     * 联单全链路详情：预约、派单、称重、轨迹、收料、结算、异常、拒收责任调查与返运，一份数据各方共用。
     */
    public record ManifestDetail(
            Manifest manifest,
            Appointment appointment,
            DispatchOrder dispatch,
            List<WeighingRecord> weighings,
            List<TrackPoint> tracks,
            PlantReceipt receipt,
            Settlement settlement,
            List<ExceptionTicket> exceptions,
            RejectInvestigation rejectInvestigation,
            RejectReturn rejectReturn) {
    }
}
