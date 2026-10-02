package com.wasteoil.reject;

/**
 * 系统基于追溯证据给出的责任建议。
 */
public enum SuggestedLiability {
    /** 商户源头混入 */
    MERCHANT_SOURCE,
    /** 运输途中污染（司机和车队承担责任） */
    TRANSPORT_CONTAMINATION,
    /** 证据不足，无法自动判定，需人工核查 */
    INCONCLUSIVE
}
