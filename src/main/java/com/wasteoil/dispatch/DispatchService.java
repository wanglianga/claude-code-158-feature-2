package com.wasteoil.dispatch;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.appointment.AppointmentRepository;
import com.wasteoil.appointment.AppointmentStatus;
import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestService;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.plant.Plant;
import com.wasteoil.plant.PlantRepository;
import com.wasteoil.weighing.PickupVerification;
import com.wasteoil.weighing.PickupVerificationRepository;
import com.wasteoil.weighing.WeighingDeviceRepository;
import com.wasteoil.weighing.WeighingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 派单与运输执行服务。
 */
@Slf4j
@Service
public class DispatchService {

    private final DispatchOrderRepository dispatchRepository;
    private final AppointmentRepository appointmentRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final PlantRepository plantRepository;
    private final StoreRepository storeRepository;
    private final DistrictRestrictionRepository restrictionRepository;
    private final ManifestRepository manifestRepository;
    private final ManifestService manifestService;
    private final WeighingRecordRepository weighingRecordRepository;
    private final WeighingDeviceRepository weighingDeviceRepository;
    private final PickupVerificationRepository verificationRepository;
    private final BarrelRepository barrelRepository;
    private final BarrelSealRepository barrelSealRepository;
    private final DispatchPlanner planner;
    private final ExceptionService exceptionService;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public DispatchService(DispatchOrderRepository dispatchRepository,
                           AppointmentRepository appointmentRepository,
                           VehicleRepository vehicleRepository,
                           DriverRepository driverRepository,
                           PlantRepository plantRepository,
                           StoreRepository storeRepository,
                           DistrictRestrictionRepository restrictionRepository,
                           ManifestRepository manifestRepository,
                           ManifestService manifestService,
                           WeighingRecordRepository weighingRecordRepository,
                           WeighingDeviceRepository weighingDeviceRepository,
                           PickupVerificationRepository verificationRepository,
                           BarrelRepository barrelRepository,
                           BarrelSealRepository barrelSealRepository,
                           DispatchPlanner planner,
                           ExceptionService exceptionService,
                           EventPublisher eventPublisher,
                           WasteOilProperties properties) {
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.plantRepository = plantRepository;
        this.storeRepository = storeRepository;
        this.restrictionRepository = restrictionRepository;
        this.manifestRepository = manifestRepository;
        this.manifestService = manifestService;
        this.weighingRecordRepository = weighingRecordRepository;
        this.weighingDeviceRepository = weighingDeviceRepository;
        this.verificationRepository = verificationRepository;
        this.barrelRepository = barrelRepository;
        this.barrelSealRepository = barrelSealRepository;
        this.planner = planner;
        this.exceptionService = exceptionService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 回收公司生成派单：校验车辆容量、商圈限行、处置厂接收时间窗，
     * 同时创建电子联单（CREATED）。
     */
    @Transactional
    public DispatchOrder plan(Long appointmentId, Long vehicleId, Long driverId, Long plantId,
                              Instant plannedStart, Instant plannedEnd, String routeNote) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> BizException.notFound("预约"));
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            throw BizException.conflict("预约状态为 " + appointment.getStatus() + "，不能派单");
        }
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> BizException.notFound("车辆"));
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> BizException.notFound("司机"));
        if (!"ACTIVE".equals(driver.getStatus())) {
            throw BizException.conflict("司机不可用");
        }
        if (!driver.getVehicleId().equals(vehicleId)) {
            throw BizException.badRequest("司机与车辆不匹配");
        }
        Plant plant = plantRepository.findById(plantId)
                .orElseThrow(() -> BizException.notFound("处置厂"));
        Store store = storeRepository.findById(appointment.getStoreId())
                .orElseThrow(() -> BizException.notFound("门店"));

        List<DistrictRestriction> restrictions = restrictionRepository.findByDistrict(store.getDistrict());
        planner.validate(appointment.getEstimatedWeightKg(), vehicle, plant, restrictions,
                plannedStart, plannedEnd);

        DispatchOrder dispatch = new DispatchOrder();
        dispatch.setAppointmentId(appointmentId);
        dispatch.setVehicleId(vehicleId);
        dispatch.setDriverId(driverId);
        dispatch.setPlantId(plantId);
        dispatch.setPlannedStart(plannedStart);
        dispatch.setPlannedEnd(plannedEnd);
        dispatch.setRouteNote(routeNote);
        dispatch.setStatus(DispatchStatus.PLANNED);
        DispatchOrder saved = dispatchRepository.save(dispatch);

        appointment.setStatus(AppointmentStatus.DISPATCHED);
        appointment.setUpdatedAt(Instant.now());
        appointmentRepository.save(appointment);

        // 派单即生成电子联单，全链路同一编号
        manifestService.createForDispatch(saved.getId(), store.getId(), plantId);

        publish(saved, "DispatchPlanned");
        return saved;
    }

    /** 司机出发前往门店 */
    @Transactional
    public DispatchOrder depart(Long dispatchId) {
        DispatchOrder dispatch = getAndCheckDriver(dispatchId);
        requireStatus(dispatch, DispatchStatus.PLANNED);
        dispatch.setStatus(DispatchStatus.EN_ROUTE);
        dispatch.setDepartedAt(Instant.now());
        dispatch.setUpdatedAt(Instant.now());
        DispatchOrder saved = dispatchRepository.save(dispatch);
        publish(saved, "DispatchDeparted");
        return saved;
    }

    /** 司机到店。超过计划时间窗 + 宽限期判定迟到并登记异常。 */
    @Transactional
    public DispatchOrder arrive(Long dispatchId) {
        DispatchOrder dispatch = getAndCheckDriver(dispatchId);
        requireStatus(dispatch, DispatchStatus.EN_ROUTE);
        Instant now = Instant.now();
        dispatch.setStatus(DispatchStatus.ARRIVED);
        dispatch.setArrivedAt(now);
        dispatch.setUpdatedAt(Instant.now());
        DispatchOrder saved = dispatchRepository.save(dispatch);

        Instant lateThreshold = dispatch.getPlannedEnd()
                .plus(properties.getDriverLateGraceMinutes(), ChronoUnit.MINUTES);
        if (now.isAfter(lateThreshold)) {
            exceptionService.raise(ExceptionType.DRIVER_LATE, "DISPATCH", dispatchId,
                    String.format("司机迟到: 计划到店 %s 前，实际 %s 到店",
                            dispatch.getPlannedEnd(), now));
        }
        publish(saved, "DispatchArrived");
        return saved;
    }

    /**
     * 到店扫码核验：核验门店二维码、称重设备、交接人并留存现场照片。
     * 核验通过后进入装车称重状态。
     */
    @Transactional
    public DispatchOrder verify(Long dispatchId, String storeQr, String deviceCode,
                                String handoverName, String photoUrls) {
        DispatchOrder dispatch = getAndCheckDriver(dispatchId);
        requireStatus(dispatch, DispatchStatus.ARRIVED);

        Appointment appointment = appointmentRepository.findById(dispatch.getAppointmentId())
                .orElseThrow(() -> BizException.notFound("预约"));
        Store store = storeRepository.findById(appointment.getStoreId())
                .orElseThrow(() -> BizException.notFound("门店"));
        if (!store.getQrCode().equals(storeQr)) {
            throw BizException.badRequest("门店二维码核验失败：与派单门店不一致");
        }
        if (deviceCode != null && !deviceCode.isBlank()) {
            var device = weighingDeviceRepository.findByCode(deviceCode)
                    .orElseThrow(() -> BizException.badRequest("称重设备编号不存在: " + deviceCode));
            if (!device.getStoreId().equals(store.getId())) {
                throw BizException.badRequest("称重设备不属于该门店");
            }
        }

        PickupVerification verification = new PickupVerification();
        verification.setDispatchId(dispatchId);
        verification.setStoreQr(storeQr);
        verification.setDeviceCode(deviceCode);
        verification.setHandoverName(handoverName);
        verification.setPhotoUrls(photoUrls);
        verification.setVerifiedBy(AuthContext.accountId() == null ? "driver" : AuthContext.accountId());
        verificationRepository.save(verification);

        dispatch.setStatus(DispatchStatus.LOADING);
        dispatch.setUpdatedAt(Instant.now());
        DispatchOrder saved = dispatchRepository.save(dispatch);
        publish(saved, "DispatchVerified");
        return saved;
    }

    /**
     * 司机封桶：装车称重后逐桶加封，登记封条编号与照片。
     * 封桶记录是处置厂拒收后划分责任的关键证据。
     */
    @Transactional
    public BarrelSeal sealBarrel(Long dispatchId, String barrelCode, String sealNo, String photoUrl) {
        DispatchOrder dispatch = getAndCheckDriver(dispatchId);
        if (dispatch.getStatus() != DispatchStatus.LOADING
                && dispatch.getStatus() != DispatchStatus.IN_TRANSIT) {
            throw BizException.conflict("派单状态为 " + dispatch.getStatus() + "，不能封桶");
        }
        Appointment appointment = appointmentRepository.findById(dispatch.getAppointmentId())
                .orElseThrow(() -> BizException.notFound("预约"));
        Barrel barrel = barrelRepository.findByCode(barrelCode)
                .orElseThrow(() -> BizException.notFound("油桶: " + barrelCode));
        if (!barrel.getStoreId().equals(appointment.getStoreId())) {
            throw BizException.badRequest("油桶 " + barrelCode + " 不属于本次回收门店");
        }
        if (!weighingRecordRepository.existsByDispatchIdAndBarrelId(dispatchId, barrel.getId())) {
            throw BizException.conflict("油桶 " + barrelCode + " 本趟尚未称重装车，不能封桶");
        }
        if (barrelSealRepository.existsByDispatchIdAndBarrelId(dispatchId, barrel.getId())) {
            throw BizException.conflict("油桶 " + barrelCode + " 本趟已加封，不能重复");
        }

        BarrelSeal seal = new BarrelSeal();
        seal.setDispatchId(dispatchId);
        seal.setBarrelId(barrel.getId());
        seal.setSealNo(sealNo);
        seal.setPhotoUrl(photoUrl);
        seal.setSealedBy(AuthContext.accountId() == null ? "driver" : AuthContext.accountId());
        BarrelSeal saved = barrelSealRepository.save(seal);

        Map<String, Object> payload = new HashMap<>();
        payload.put("dispatchId", dispatchId);
        payload.put("barrelCode", barrelCode);
        payload.put("sealNo", sealNo);
        payload.put("sealedBy", saved.getSealedBy());
        eventPublisher.publish(KafkaTopics.DISPATCH_EVENTS, "dispatch-" + dispatchId,
                "BarrelSealed", payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<BarrelSeal> listSeals(Long dispatchId) {
        return barrelSealRepository.findByDispatchId(dispatchId);
    }

    /** 装车完成起运：要求至少有一条称重记录。联单进入 IN_TRANSIT。 */
    @Transactional
    public DispatchOrder loadComplete(Long dispatchId) {
        DispatchOrder dispatch = getAndCheckDriver(dispatchId);
        requireStatus(dispatch, DispatchStatus.LOADING);
        long count = weighingRecordRepository.findByDispatchId(dispatchId).size();
        if (count == 0) {
            throw BizException.conflict("尚无称重记录，不能起运");
        }
        dispatch.setStatus(DispatchStatus.IN_TRANSIT);
        dispatch.setLoadDoneAt(Instant.now());
        dispatch.setUpdatedAt(Instant.now());
        DispatchOrder saved = dispatchRepository.save(dispatch);

        Manifest manifest = manifestService.getByDispatch(dispatchId);
        manifestService.transition(manifest, ManifestStatus.IN_TRANSIT, "ManifestInTransit");

        publish(saved, "DispatchInTransit");
        return saved;
    }

    /** 到达处置厂 */
    @Transactional
    public DispatchOrder arrivePlant(Long dispatchId) {
        DispatchOrder dispatch = getAndCheckDriver(dispatchId);
        requireStatus(dispatch, DispatchStatus.IN_TRANSIT);
        dispatch.setStatus(DispatchStatus.ARRIVED_PLANT);
        dispatch.setPlantArrivedAt(Instant.now());
        dispatch.setUpdatedAt(Instant.now());
        DispatchOrder saved = dispatchRepository.save(dispatch);
        publish(saved, "DispatchArrivedPlant");
        return saved;
    }

    /** 联单闭环后由收料流程调用，完成派单 */
    @Transactional
    public DispatchOrder complete(Long dispatchId) {
        DispatchOrder dispatch = dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> BizException.notFound("派单"));
        if (dispatch.getStatus() != DispatchStatus.ARRIVED_PLANT) {
            throw BizException.conflict("派单当前状态不能完成: " + dispatch.getStatus());
        }
        dispatch.setStatus(DispatchStatus.COMPLETED);
        dispatch.setCompletedAt(Instant.now());
        dispatch.setUpdatedAt(Instant.now());

        Appointment appointment = appointmentRepository.findById(dispatch.getAppointmentId())
                .orElseThrow(() -> BizException.notFound("预约"));
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setUpdatedAt(Instant.now());
        appointmentRepository.save(appointment);

        DispatchOrder saved = dispatchRepository.save(dispatch);
        publish(saved, "DispatchCompleted");
        return saved;
    }

    /**
     * 定时扫描：计划时间窗已过（含宽限期）仍未到店的派单，登记司机迟到异常。
     */
    @Transactional
    public int scanLateDispatches() {
        Instant threshold = Instant.now()
                .minus(properties.getDriverLateGraceMinutes(), ChronoUnit.MINUTES);
        int raised = 0;
        for (DispatchOrder d : dispatchRepository.findByStatusIn(
                List.of(DispatchStatus.PLANNED, DispatchStatus.EN_ROUTE))) {
            if (d.getPlannedEnd().isBefore(threshold)) {
                exceptionService.raise(ExceptionType.DRIVER_LATE, "DISPATCH", d.getId(),
                        String.format("司机未按时到店: 计划到店 %s 前，至今未到", d.getPlannedEnd()));
                raised++;
            }
        }
        return raised;
    }

    @Transactional(readOnly = true)
    public DispatchOrder get(Long id) {
        return dispatchRepository.findById(id).orElseThrow(() -> BizException.notFound("派单"));
    }

    @Transactional(readOnly = true)
    public List<DispatchOrder> list(String status) {
        if (status == null || status.isBlank()) {
            return dispatchRepository.findAll();
        }
        return dispatchRepository.findByStatus(DispatchStatus.valueOf(status.toUpperCase()));
    }

    private DispatchOrder getAndCheckDriver(Long dispatchId) {
        return dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> BizException.notFound("派单"));
    }

    private void requireStatus(DispatchOrder dispatch, DispatchStatus expected) {
        if (dispatch.getStatus() != expected) {
            throw BizException.conflict(
                    "派单状态为 " + dispatch.getStatus() + "，期望 " + expected);
        }
    }

    private void publish(DispatchOrder dispatch, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dispatchId", dispatch.getId());
        payload.put("appointmentId", dispatch.getAppointmentId());
        payload.put("vehicleId", dispatch.getVehicleId());
        payload.put("driverId", dispatch.getDriverId());
        payload.put("plantId", dispatch.getPlantId());
        payload.put("status", dispatch.getStatus().name());
        eventPublisher.publish(KafkaTopics.DISPATCH_EVENTS,
                "dispatch-" + dispatch.getId(), type, payload);
    }
}
