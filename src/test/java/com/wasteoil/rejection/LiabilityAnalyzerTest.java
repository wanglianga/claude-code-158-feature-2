package com.wasteoil.rejection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒收责任建议规则：基于交接照片、封桶记录、运输轨迹与到厂检测的证据链。
 */
class LiabilityAnalyzerTest {

    @Test
    void 全部加封且封条完好_判定商户源头混入() {
        String result = LiabilityAnalyzer.suggest("CONTAMINATED", "INTACT", 2, 2, false);
        assertThat(result).isEqualTo(LiabilityAnalyzer.MERCHANT_SOURCE);
    }

    @Test
    void 封条破损_判定运输中污染() {
        String result = LiabilityAnalyzer.suggest("CONTAMINATED", "BROKEN", 2, 2, false);
        assertThat(result).isEqualTo(LiabilityAnalyzer.TRANSPORT);
    }

    @Test
    void 轨迹缺失_判定运输中污染() {
        String result = LiabilityAnalyzer.suggest("CONTAMINATED", "INTACT", 2, 2, true);
        assertThat(result).isEqualTo(LiabilityAnalyzer.TRANSPORT);
    }

    @Test
    void 未留封桶记录_运输方无法自证_判定运输中污染() {
        String result = LiabilityAnalyzer.suggest("CONTAMINATED", "UNKNOWN", 2, 0, false);
        assertThat(result).isEqualTo(LiabilityAnalyzer.TRANSPORT);
    }

    @Test
    void 部分加封且封条状态未知_证据不足() {
        String result = LiabilityAnalyzer.suggest("CONTAMINATED", "UNKNOWN", 2, 1, false);
        assertThat(result).isEqualTo(LiabilityAnalyzer.UNDETERMINED);
    }

    @Test
    void 非污染类拒收_不自动建议() {
        String result = LiabilityAnalyzer.suggest("OK", "INTACT", 2, 2, false);
        assertThat(result).isEqualTo(LiabilityAnalyzer.UNDETERMINED);
    }
}
