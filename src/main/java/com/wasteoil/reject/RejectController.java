package com.wasteoil.reject;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 拒收责任追溯与处置：责任调查查询、责任认定（费用来源）、返运路线/临时存放点/二次处理结果。
 */
@RestController
@RequestMapping("/api/rejects")
public class RejectController {

    private final RejectInvestigationService service;

    public RejectController(RejectInvestigationService service) {
        this.service = service;
    }

    /** 查询拒收责任调查（证据快照 + 系统建议 + 认定结论），监管/商户/处置厂均可追溯 */
    @GetMapping("/by-manifest/{manifestId}")
    public ApiResult<RejectInvestigation> investigation(@PathVariable Long manifestId) {
        return ApiResult.ok(service.getByManifest(manifestId));
    }

    /** 查询拒收批次返运记录（路线/临时存放点/二次处理结果） */
    @GetMapping("/by-manifest/{manifestId}/return")
    public ApiResult<RejectReturn> returnRecord(@PathVariable Long manifestId) {
        return ApiResult.ok(service.getReturn(manifestId));
    }

    /** 回收公司认定责任方、额外处理费与费用来源（可认定无需返运） */
    @PostMapping("/by-manifest/{manifestId}/confirm")
    public ApiResult<RejectInvestigation> confirm(@PathVariable Long manifestId,
                                                   @Valid @RequestBody ConfirmRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(service.confirm(manifestId, req.liabilityParty(),
                req.extraFee(), req.feeSource(), Boolean.TRUE.equals(req.noReturnRequired())));
    }

    /** 拒收批次返运离厂：登记返运路线、车辆、司机 */
    @PostMapping("/by-manifest/{manifestId}/return")
    public ApiResult<RejectReturn> registerReturn(@PathVariable Long manifestId,
                                                   @Valid @RequestBody ReturnRequest req) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.DRIVER);
        return ApiResult.ok(service.registerReturn(manifestId, req.returnRoute(),
                req.vehicleId(), req.driverId(), req.returnedBy()));
    }

    /** 返运批次到达临时存放点 */
    @PostMapping("/by-manifest/{manifestId}/storage")
    public ApiResult<RejectReturn> arriveStorage(@PathVariable Long manifestId,
                                                 @Valid @RequestBody StorageRequest req) {
        AuthContext.require(ActorRole.RECYCLER, ActorRole.DRIVER);
        return ApiResult.ok(service.arriveStorage(manifestId, req.storagePoint(),
                req.storagePhotoUrl()));
    }

    /** 登记二次处理结果（分拣后接收/无害化处理/退回商户），拒收批次闭环 */
    @PostMapping("/by-manifest/{manifestId}/reprocess")
    public ApiResult<RejectReturn> reprocess(@PathVariable Long manifestId,
                                             @Valid @RequestBody ReprocessRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(service.completeReprocess(manifestId, req.result(),
                req.reprocessWeightKg(), req.note()));
    }

    public record ConfirmRequest(
            @NotNull(message = "责任方不能为空") LiabilityParty liabilityParty,
            @DecimalMin(value = "0.0", message = "额外费用不能为负") BigDecimal extraFee,
            String feeSource,
            Boolean noReturnRequired) {
    }

    public record ReturnRequest(
            @NotBlank(message = "返运路线不能为空") String returnRoute,
            Long vehicleId,
            Long driverId,
            @NotBlank(message = "返运经办人不能为空") String returnedBy) {
    }

    public record StorageRequest(
            @NotBlank(message = "临时存放点不能为空") String storagePoint,
            String storagePhotoUrl) {
    }

    public record ReprocessRequest(
            @NotNull(message = "二次处理结果不能为空") ReprocessResult result,
            @NotNull(message = "二次处理重量不能为空")
            @DecimalMin(value = "0.0", message = "二次处理重量不能为负") BigDecimal reprocessWeightKg,
            String note) {
    }
}
