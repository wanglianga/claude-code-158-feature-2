package com.wasteoil.reject;

/**
 * 拒收批次二次处理结果。
 */
public enum ReprocessResult {
    /** 分拣剔除厨余后，废油被合规接收 */
    RE_ACCEPTED,
    /** 整批移交无害化处理 */
    DISPOSED,
    /** 退回商户自行处理 */
    RETURNED_MERCHANT
}
