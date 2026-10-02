package com.wasteoil.reject;

/**
 * 拒收责任方认定结论。
 */
public enum LiabilityParty {
    /** 商户源头混入：交接/称重照片可见厨余残渣，或封条完好到厂，污染只能来自门店 */
    MERCHANT,
    /** 司机车队：封条破损/缺失，或运输轨迹存在异常停留，污染发生在运输环节 */
    DRIVER_FLEET,
    /** 证据不足，无法认定（不产生额外费用，需人工继续核查） */
    NONE
}
