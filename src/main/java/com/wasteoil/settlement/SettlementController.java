package com.wasteoil.settlement;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 商户/回收公司查询结算单 */
    @GetMapping
    public ApiResult<List<Settlement>> listByStore(@RequestParam Long storeId) {
        AuthContext.require(ActorRole.MERCHANT, ActorRole.RECYCLER, ActorRole.REGULATOR);
        return ApiResult.ok(settlementService.listByStore(storeId));
    }

    @GetMapping("/by-manifest/{manifestId}")
    public ApiResult<Settlement> byManifest(@PathVariable Long manifestId) {
        return ApiResult.ok(settlementService.getByManifest(manifestId));
    }

    /** 额外费用单查询：费用来源明确（哪起拒收、谁承担、为什么），避免互相推责 */
    @GetMapping("/fees")
    public ApiResult<List<SettlementFee>> fees(@RequestParam(required = false) Long storeId,
                                               @RequestParam(required = false) Long manifestId) {
        AuthContext.require(ActorRole.MERCHANT, ActorRole.RECYCLER, ActorRole.REGULATOR);
        if (manifestId != null) {
            return ApiResult.ok(settlementService.listFeesByManifest(manifestId));
        }
        if (storeId != null) {
            return ApiResult.ok(settlementService.listFeesByStore(storeId));
        }
        throw com.wasteoil.common.BizException.badRequest("storeId 与 manifestId 至少提供一个");
    }
}
