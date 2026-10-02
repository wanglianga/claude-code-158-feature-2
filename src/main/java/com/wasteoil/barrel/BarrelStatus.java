package com.wasteoil.barrel;

public enum BarrelStatus {
    /** 在门店 */
    AT_STORE,
    /** 已装车 */
    LOADED,
    /** 已加封一次性封条（装车称重后、起运前由司机封桶） */
    SEALED,
    /** 运输中 */
    IN_TRANSIT,
    /** 已到厂 */
    AT_PLANT,
    /** 已清空返还 */
    EMPTIED
}
