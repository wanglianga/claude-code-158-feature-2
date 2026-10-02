package com.wasteoil.dispatch;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/dispatches")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 回收公司生成派单 */
    @PostMapping
    public ApiResult<DispatchOrder> plan(@Valid @RequestBody PlanRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.plan(req.appointmentId(), req.vehicleId(), req.driverId(),
                req.plantId(), req.plannedStart(), req.plannedEnd(), req.routeNote()));
    }

    /** 司机出发 */
    @PostMapping("/{id}/depart")
    public ApiResult<DispatchOrder> depart(@PathVariable Long id) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.depart(id));
    }

    /** 司机到店 */
    @PostMapping("/{id}/arrive")
    public ApiResult<DispatchOrder> arrive(@PathVariable Long id) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.arrive(id));
    }

    /** 到店扫码核验 */
    @PostMapping("/{id}/verify")
    public ApiResult<DispatchOrder> verify(@PathVariable Long id,
                                           @Valid @RequestBody VerifyRequest req) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.verify(id, req.storeQr(), req.deviceCode(),
                req.handoverName(), req.photoUrls()));
    }

    /** 司机封桶：装车后逐桶加封并留存封条编号与照片 */
    @PostMapping("/{id}/seals")
    public ApiResult<BarrelSeal> seal(@PathVariable Long id,
                                      @Valid @RequestBody SealRequest req) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.sealBarrel(id, req.barrelCode(), req.sealNo(), req.photoUrl()));
    }

    /** 本趟封桶记录查询 */
    @GetMapping("/{id}/seals")
    public ApiResult<List<BarrelSeal>> seals(@PathVariable Long id) {
        return ApiResult.ok(dispatchService.listSeals(id));
    }

    /** 装车完成起运 */
    @PostMapping("/{id}/load-complete")
    public ApiResult<DispatchOrder> loadComplete(@PathVariable Long id) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.loadComplete(id));
    }

    /** 到达处置厂 */
    @PostMapping("/{id}/arrive-plant")
    public ApiResult<DispatchOrder> arrivePlant(@PathVariable Long id) {
        AuthContext.require(ActorRole.DRIVER, ActorRole.RECYCLER);
        return ApiResult.ok(dispatchService.arrivePlant(id));
    }

    @GetMapping("/{id}")
    public ApiResult<DispatchOrder> get(@PathVariable Long id) {
        return ApiResult.ok(dispatchService.get(id));
    }

    @GetMapping
    public ApiResult<List<DispatchOrder>> list(@RequestParam(required = false) String status) {
        return ApiResult.ok(dispatchService.list(status));
    }

    public record PlanRequest(
            @NotNull(message = "预约不能为空") Long appointmentId,
            @NotNull(message = "车辆不能为空") Long vehicleId,
            @NotNull(message = "司机不能为空") Long driverId,
            @NotNull(message = "处置厂不能为空") Long plantId,
            @NotNull(message = "计划开始时间不能为空") Instant plannedStart,
            @NotNull(message = "计划结束时间不能为空") Instant plannedEnd,
            String routeNote) {
    }

    public record VerifyRequest(
            @NotBlank(message = "门店二维码不能为空") String storeQr,
            String deviceCode,
            @NotBlank(message = "交接人不能为空") String handoverName,
            String photoUrls) {
    }

    public record SealRequest(
            @NotBlank(message = "油桶编号不能为空") String barrelCode,
            @NotBlank(message = "封条编号不能为空") String sealNo,
            String photoUrl) {
    }
}
