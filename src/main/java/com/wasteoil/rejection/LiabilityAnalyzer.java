package com.wasteoil.rejection;

/**
 * 拒收责任建议规则（纯逻辑，可单测）。
 *
 * 证据链：商户交接照片 → 司机封桶记录 → 运输轨迹 → 到厂检测结果（含封条核验）。
 * 判定思路：
 * <ul>
 *   <li>到厂封条破损 → 运输环节被打开过，运输中污染，司机与车队担责；</li>
 *   <li>运输轨迹缺失 → 运输过程无法自证，司机与车队担责；</li>
 *   <li>未留任何封桶记录 → 运输方无法证明未开封，司机与车队担责；</li>
 *   <li>全部油桶加封且到厂封条完好 → 污染在装车前已存在，商户源头混入；</li>
 *   <li>其余情况证据不足，待人工定责。</li>
 * </ul>
 */
public final class LiabilityAnalyzer {

    /** 商户源头混入 */
    public static final String MERCHANT_SOURCE = "MERCHANT_SOURCE";
    /** 运输中污染 */
    public static final String TRANSPORT = "TRANSPORT";
    /** 证据不足，待人工定责 */
    public static final String UNDETERMINED = "UNDETERMINED";

    private LiabilityAnalyzer() {
    }

    /**
     * @param qualityCheck   到厂质检结果（OK / CONTAMINATED）
     * @param sealCheck      到厂封条核验（INTACT / BROKEN / UNKNOWN）
     * @param weighedBarrels 本趟称重装车桶数
     * @param sealedBarrels  本趟留有封桶记录的桶数
     * @param trackMissing   运输轨迹是否缺失
     */
    public static String suggest(String qualityCheck, String sealCheck,
                                 int weighedBarrels, int sealedBarrels, boolean trackMissing) {
        if (!"CONTAMINATED".equals(qualityCheck)) {
            // 非污染类拒收（如重量不符），不自动建议，人工定责
            return UNDETERMINED;
        }
        if ("BROKEN".equals(sealCheck)) {
            return TRANSPORT;
        }
        if (trackMissing) {
            return TRANSPORT;
        }
        if (sealedBarrels == 0) {
            return TRANSPORT;
        }
        if ("INTACT".equals(sealCheck) && sealedBarrels >= weighedBarrels) {
            return MERCHANT_SOURCE;
        }
        return UNDETERMINED;
    }
}
