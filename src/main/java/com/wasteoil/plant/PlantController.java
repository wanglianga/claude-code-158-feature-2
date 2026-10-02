package com.wasteoil.plant;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/receipts")
public class PlantController {

    private final PlantService plantService;

    public PlantController(PlantService plantService) {
        this.plantService = plantService;
    }

    /** 处置厂收料：二次称重 + 质检 + 封条核验 + 接收/拒收 */
    @PostMapping
    public ApiResult<PlantReceipt> receive(@Valid @RequestBody ReceiptRequest req) {
        AuthContext.require(ActorRole.PLANT);
        return ApiResult.ok(plantService.receive(req.manifestId(), req.arrivalWeightKg(),
                req.barrelCount(), req.qualityCheck(), req.sealCheck(), req.decision(),
                req.rejectReason(), req.receivedBy()));
    }

    @GetMapping("/by-manifest/{manifestId}")
    public ApiResult<PlantReceipt> getByManifest(@PathVariable Long manifestId) {
        return ApiResult.ok(plantService.getByManifest(manifestId));
    }

    public record ReceiptRequest(
            @NotNull(message = "联单不能为空") Long manifestId,
            @NotNull(message = "到厂重量不能为空")
            @DecimalMin(value = "0.0", message = "到厂重量不能为负") BigDecimal arrivalWeightKg,
            @NotNull(message = "到厂桶数不能为空") @Min(value = 0, message = "到厂桶数不能为负") Integer barrelCount,
            @NotBlank(message = "质检结果不能为空")
            @Pattern(regexp = "OK|CONTAMINATED", message = "质检结果只能是 OK 或 CONTAMINATED") String qualityCheck,
            @Pattern(regexp = "INTACT|BROKEN|UNKNOWN", message = "封条核验只能是 INTACT、BROKEN 或 UNKNOWN")
            String sealCheck,
            @NotBlank(message = "接收决定不能为空")
            @Pattern(regexp = "ACCEPT|REJECT", message = "接收决定只能是 ACCEPT 或 REJECT") String decision,
            String rejectReason,
            @NotBlank(message = "收料人不能为空") String receivedBy) {
    }
}
