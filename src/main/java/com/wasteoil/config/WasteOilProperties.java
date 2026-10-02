package com.wasteoil.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "wasteoil")
public class WasteOilProperties {

    /** 结算单价（元/公斤） */
    private BigDecimal unitPricePerKg = new BigDecimal("3.20");

    /** 运输损耗率阈值 */
    private BigDecimal maxLossRate = new BigDecimal("0.03");

    /** 称重设备离线判定（分钟） */
    private int deviceOfflineMinutes = 5;

    /** 司机迟到宽限（分钟） */
    private int driverLateGraceMinutes = 30;

    /** 运输途中轨迹缺失判定（分钟） */
    private int trackMissingMinutes = 10;

    /** 风险分析窗口（天） */
    private int riskWindowDays = 90;

    /** 产量/预期比值阈值 */
    private BigDecimal riskYieldRatio = new BigDecimal("0.3");

    /** 拒收责任判定：相邻轨迹点时间间隔超过该分钟数且位移很小，计为一次异常停留 */
    private int abnormalStopMinutes = 20;

    /** 拒收责任判定：异常停留的位移半径（米） */
    private int abnormalStopRadiusMeters = 500;
}
