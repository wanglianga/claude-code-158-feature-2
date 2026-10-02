package com.wasteoil.reject;

import com.wasteoil.track.TrackPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒收责任分析器单元测试：封桶封条 + 运输轨迹异常停留 → 责任建议。
 */
class RejectLiabilityAnalyzerTest {

    private RejectLiabilityAnalyzer.EvidenceBundle evidence(int total, int sealed,
                                                            Boolean sealIntact, int stops) {
        return new RejectLiabilityAnalyzer.EvidenceBundle(total, sealed,
                total > 0 && sealed == total, sealIntact, 5, stops, 1, 2);
    }

    @Test
    void 全部封桶且封条完好_判定商户源头() {
        RejectLiabilityAnalyzer.Verdict v =
                RejectLiabilityAnalyzer.analyze(evidence(2, 2, Boolean.TRUE, 0));
        assertThat(v.liability()).isEqualTo(SuggestedLiability.MERCHANT_SOURCE);
        assertThat(v.reason()).contains("封条全部完好");
    }

    @Test
    void 全部封桶但封条破损_判定运输污染() {
        RejectLiabilityAnalyzer.Verdict v =
                RejectLiabilityAnalyzer.analyze(evidence(2, 2, Boolean.FALSE, 1));
        assertThat(v.liability()).isEqualTo(SuggestedLiability.TRANSPORT_CONTAMINATION);
        assertThat(v.reason()).contains("封条破损/缺失");
    }

    @Test
    void 部分封桶且存在异常停留_判定运输污染() {
        RejectLiabilityAnalyzer.Verdict v =
                RejectLiabilityAnalyzer.analyze(evidence(3, 1, Boolean.FALSE, 2));
        assertThat(v.liability()).isEqualTo(SuggestedLiability.TRANSPORT_CONTAMINATION);
        assertThat(v.reason()).contains("未履行全程封桶义务", "异常停留");
    }

    @Test
    void 未全部封桶且无运输异常证据_无法自动判定() {
        RejectLiabilityAnalyzer.Verdict v =
                RejectLiabilityAnalyzer.analyze(evidence(3, 2, Boolean.TRUE, 0));
        assertThat(v.liability()).isEqualTo(SuggestedLiability.INCONCLUSIVE);
        assertThat(v.reason()).contains("人工核查");
    }

    @Test
    void 全部封桶但缺少到厂封条结论_无法自动判定() {
        RejectLiabilityAnalyzer.Verdict v =
                RejectLiabilityAnalyzer.analyze(evidence(2, 2, null, 0));
        assertThat(v.liability()).isEqualTo(SuggestedLiability.INCONCLUSIVE);
    }

    @Test
    void 司机未封桶_到厂报封条缺失且无异常停留_不能单独推定运输责任() {
        // 从未加封封条（0/2），处置厂 sealIntact=false 仅表示"无封条"，无异常停留时转人工核查
        RejectLiabilityAnalyzer.Verdict v =
                RejectLiabilityAnalyzer.analyze(evidence(2, 0, Boolean.FALSE, 0));
        assertThat(v.liability()).isEqualTo(SuggestedLiability.INCONCLUSIVE);
    }

    @Test
    void 轨迹长时间小位移_识别为异常停留() {
        Instant t0 = Instant.parse("2026-10-02T02:00:00Z");
        List<TrackPoint> points = List.of(
                point(30.66, 104.06, t0),
                point(30.6601, 104.0601, t0.plus(30, ChronoUnit.MINUTES)),  // 30 分钟几乎未移动
                point(30.60, 104.10, t0.plus(60, ChronoUnit.MINUTES)));   // 恢复行驶
        List<RejectLiabilityAnalyzer.AbnormalStop> stops =
                RejectLiabilityAnalyzer.detectAbnormalStops(points, 20, 500);
        assertThat(stops).hasSize(1);
        assertThat(stops.get(0).durationMinutes()).isEqualTo(30);
    }

    @Test
    void 轨迹连续行驶_无异常停留() {
        Instant t0 = Instant.parse("2026-10-02T02:00:00Z");
        List<TrackPoint> points = List.of(
                point(30.66, 104.06, t0),
                point(30.63, 104.08, t0.plus(10, ChronoUnit.MINUTES)),
                point(30.60, 104.10, t0.plus(20, ChronoUnit.MINUTES)));
        List<RejectLiabilityAnalyzer.AbnormalStop> stops =
                RejectLiabilityAnalyzer.detectAbnormalStops(points, 20, 500);
        assertThat(stops).isEmpty();
    }

    @Test
    void 长时间但位移超出半径_不算异常停留() {
        Instant t0 = Instant.parse("2026-10-02T02:00:00Z");
        // 30 分钟后移动了约 4 公里，属于正常行驶
        List<TrackPoint> points = List.of(
                point(30.66, 104.06, t0),
                point(30.62, 104.08, t0.plus(30, ChronoUnit.MINUTES)));
        List<RejectLiabilityAnalyzer.AbnormalStop> stops =
                RejectLiabilityAnalyzer.detectAbnormalStops(points, 20, 500);
        assertThat(stops).isEmpty();
    }

    private TrackPoint point(double lat, double lng, Instant at) {
        TrackPoint p = new TrackPoint();
        p.setLat(BigDecimal.valueOf(lat));
        p.setLng(BigDecimal.valueOf(lng));
        p.setRecordedAt(at);
        return p;
    }
}
