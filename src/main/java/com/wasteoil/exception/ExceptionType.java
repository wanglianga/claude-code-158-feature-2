package com.wasteoil.exception;

/**
 * 异常类型。BLOCKING 类型会阻断联单闭环，必须处理完毕后联单才能进入 VERIFIED。
 */
public enum ExceptionType {
    /** 商户临时取消 */
    MERCHANT_CANCEL(false),
    /** 司机迟到 */
    DRIVER_LATE(false),
    /** 油桶混入厨余 */
    BARREL_CONTAMINATED(false),
    /** 称重设备离线 */
    DEVICE_OFFLINE(false),
    /** 司机手动补录 */
    MANUAL_ENTRY(false),
    /** 运输轨迹缺失 */
    TRACK_MISSING(true),
    /** 处置厂拒收 */
    PLANT_REJECT(false),
    /** 拒收责任认定为商户源头混入（产生商户额外处理费） */
    REJECT_LIABILITY_MERCHANT(false),
    /** 拒收责任认定为运输途中污染（司机和车队承担责任） */
    REJECT_LIABILITY_TRANSPORT(false),
    /** 商户质疑重量 */
    WEIGHT_DISPUTE(false),
    /** 监管抽查 */
    REGULATORY_INSPECTION(false),
    /** 运输损耗异常 */
    TRANSPORT_LOSS_ABNORMAL(true),
    /** 油桶数量不符 */
    BARREL_COUNT_MISMATCH(true);

    private final boolean blocking;

    ExceptionType(boolean blocking) {
        this.blocking = blocking;
    }

    public boolean isBlocking() {
        return blocking;
    }
}
