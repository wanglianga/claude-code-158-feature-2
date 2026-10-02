package com.wasteoil.rejection;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 拒收责任认定：定责、返运、二次处理与结案。
 * 查询接口各端共用，看到的都是同一份认定单与证据快照。
 */
@RestController
@RequestMapping("/api/rejections")
public class RejectionController {

    private final RejectionService rejectionService;

    public RejectionController(RejectionService rejectionService) {
        this.rejectionService = rejectionService;
    }

    /** 案件详情：认定单 + 证据快照（交接照片/封桶记录/轨迹/到厂检测） */
    @GetMapping("/{id}")
    public ApiResult<RejectionCaseDetail> detail(@PathVariable Long id) {
        RejectionCase c = rejectionService.get(id);
        return ApiResult.ok(new RejectionCaseDetail(c, rejectionService.parseEvidence(c)));
    }

    @GetMapping("/by-manifest/{manifestId}")
    public ApiResult<RejectionCaseDetail> byManifest(@PathVariable Long manifestId) {
        RejectionCase c = rejectionService.getByManifest(manifestId);
        return ApiResult.ok(new RejectionCaseDetail(c, rejectionService.parseEvidence(c)));
    }

    @GetMapping
    public ApiResult<List<RejectionCase>> list(@RequestParam(required = false) String status) {
        return ApiResult.ok(rejectionService.list(status));
    }

    /** 回收公司定责：商户源头混入 → 商户承担额外处理费；运输中污染 → 司机与车队承担 */
    @PostMapping("/{id}/determine")
    public ApiResult<RejectionCase> determine(@PathVariable Long id,
                                              @Valid @RequestBody DetermineRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(rejectionService.determine(id, req.liabilityParty(),
                req.note(), req.extraFeeAmount()));
    }

    /** 安排返运：记录返运路线与临时存放点 */
    @PostMapping("/{id}/return")
    public ApiResult<RejectionCase> arrangeReturn(@PathVariable Long id,
                                                  @Valid @RequestBody ReturnRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(rejectionService.arrangeReturn(id, req.returnRoute(), req.tempStoragePoint()));
    }

    /** 登记二次处理结果 */
    @PostMapping("/{id}/reprocess")
    public ApiResult<RejectionCase> reprocess(@PathVariable Long id,
                                              @Valid @RequestBody ReprocessRequest req) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(rejectionService.recordReprocessing(id, req.secondaryResult()));
    }

    /** 结案：联动处理拒收异常工单 */
    @PostMapping("/{id}/close")
    public ApiResult<RejectionCase> close(@PathVariable Long id) {
        AuthContext.require(ActorRole.RECYCLER);
        return ApiResult.ok(rejectionService.close(id));
    }

    public record RejectionCaseDetail(RejectionCase rejectionCase, Map<String, Object> evidence) {
    }

    public record DetermineRequest(
            @NotBlank(message = "责任方不能为空") String liabilityParty,
            String note,
            @DecimalMin(value = "0.0", message = "额外处理费不能为负") BigDecimal extraFeeAmount) {
    }

    public record ReturnRequest(
            @NotBlank(message = "返运路线不能为空") String returnRoute,
            @NotBlank(message = "临时存放点不能为空") String tempStoragePoint) {
    }

    public record ReprocessRequest(
            @NotBlank(message = "二次处理结果不能为空") String secondaryResult) {
    }
}
