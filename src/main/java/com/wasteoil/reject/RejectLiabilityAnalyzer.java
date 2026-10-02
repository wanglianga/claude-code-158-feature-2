package com.wasteoil.reject;

import com.wasteoil.track.TrackPoint;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 拒收责任分析器：依据追溯证据给出责任建议（纯逻辑，无数据库依赖，便于单测）。
 *
 * 判定原则：
 * - 封桶封条是切断运输环节嫌疑的关键证据：全部桶加封且到厂封条完好，运输途中无法开封，
 *   污染只能来自商户源头；
 * - 起运前已加封、到厂封条破损/缺失，或运输轨迹存在异常停留，污染发生在运输环节，
 *   由司机和车队承担责任；
 * - 封桶不完整或关键证据缺失时给出 INCONCLUSIVE，由回收公司人工核查，不自动定责。
 */
public final class RejectLiabilityAnalyzer {

    private RejectLiabilityAnalyzer() {
    }

    /**
     * 分析器输入的证据集合（由服务层从各业务表聚合后传入）。
     */
    public record EvidenceBundle(int totalBarrelCount,
                                 int sealedBarrelCount,
                                 boolean allSealed,
                                 Boolean sealIntactAtPlant,
                                 int trackPointCount,
                                 int abnormalStopCount,
                                 int handoverPhotoCount,
                                 int weighingPhotoCount) {
    }

    public record Verdict(SuggestedLiability liability, String reason) {
    }

    public static Verdict analyze(EvidenceBundle e) {
        // 仅当确实加封过封条时，"封条破损/缺失"才构成运输环节破坏封条的证据；
        // 从未封桶时 sealIntact=false 只代表"无封条"，不能单独指向运输污染
        boolean sealBroken = Boolean.FALSE.equals(e.sealIntactAtPlant()) && e.sealedBarrelCount() > 0;
        boolean sealIntact = Boolean.TRUE.equals(e.sealIntactAtPlant());

        // 1. 全部桶已封桶
        if (e.allSealed()) {
            if (sealIntact) {
                return new Verdict(SuggestedLiability.MERCHANT_SOURCE,
                        "全部 " + e.totalBarrelCount() + " 桶起运前加封一次性封条，到厂核验封条全部完好，"
                                + "运输途中无法开封投料，污染来自商户交接源头");
            }
            if (sealBroken) {
                return new Verdict(SuggestedLiability.TRANSPORT_CONTAMINATION,
                        "全部 " + e.totalBarrelCount() + " 桶起运前已加封，到厂封条破损/缺失"
                                + (e.abnormalStopCount() > 0 ? "，且运输途中存在 " + e.abnormalStopCount() + " 次异常停留" : "")
                                + "，污染发生在运输环节，司机和车队承担责任");
            }
        }

        // 2. 部分桶封桶：封条破损/异常停留指向运输环节；封条完好也只能证明已封的桶，未封桶无法排除
        if (sealBroken || e.abnormalStopCount() > 0) {
            List<String> why = new ArrayList<>();
            if (!e.allSealed()) {
                why.add("仅 " + e.sealedBarrelCount() + "/" + e.totalBarrelCount() + " 桶封桶，司机未履行全程封桶义务");
            }
            if (sealBroken) {
                why.add("到厂封条破损/缺失");
            }
            if (e.abnormalStopCount() > 0) {
                why.add("运输途中 " + e.abnormalStopCount() + " 次异常停留");
            }
            return new Verdict(SuggestedLiability.TRANSPORT_CONTAMINATION,
                    String.join("；", why) + "，污染指向运输环节，司机和车队承担责任");
        }

        // 3. 封桶不完整且无运输异常证据：无法区分源头与运输，转人工核查
        if (!e.allSealed()) {
            return new Verdict(SuggestedLiability.INCONCLUSIVE,
                    "仅 " + e.sealedBarrelCount() + "/" + e.totalBarrelCount() + " 桶封桶，"
                            + "未封桶无法排除运输途中污染，需结合交接照片与运输监控人工核查");
        }

        // 4. 已全部封桶但处置厂未给出封条核验结论
        return new Verdict(SuggestedLiability.INCONCLUSIVE,
                "缺少到厂封条核验结论，无法自动判定责任，需人工核查");
    }

    /**
     * 检测运输途中异常停留：相邻轨迹点时间间隔达到阈值且位移在半径内，计一次。
     *
     * @return 异常停留区间明细（起止时间 + 经纬度）
     */
    public static List<AbnormalStop> detectAbnormalStops(List<TrackPoint> points,
                                                         int stopMinutes, int radiusMeters) {
        List<AbnormalStop> stops = new ArrayList<>();
        List<TrackPoint> sorted = points.stream()
                .sorted((a, b) -> a.getRecordedAt().compareTo(b.getRecordedAt()))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            TrackPoint prev = sorted.get(i - 1);
            TrackPoint cur = sorted.get(i);
            long minutes = Duration.between(prev.getRecordedAt(), cur.getRecordedAt()).toMinutes();
            double meters = haversine(prev.getLat(), prev.getLng(), cur.getLat(), cur.getLng());
            if (minutes >= stopMinutes && meters <= radiusMeters) {
                stops.add(new AbnormalStop(prev.getRecordedAt(), cur.getRecordedAt(),
                        prev.getLat(), prev.getLng(), (int) minutes, (int) Math.round(meters)));
            }
        }
        return stops;
    }

    /** 两点球面距离（米） */
    static double haversine(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double r = 6_371_000;
        double la1 = Math.toRadians(lat1.doubleValue());
        double la2 = Math.toRadians(lat2.doubleValue());
        double dla = Math.toRadians(lat2.subtract(lat1).doubleValue());
        double dlo = Math.toRadians(lng2.subtract(lng1).doubleValue());
        double a = Math.sin(dla / 2) * Math.sin(dla / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 一次异常停留。
     */
    public record AbnormalStop(Instant from, Instant to, BigDecimal lat, BigDecimal lng,
                               int durationMinutes, int distanceMeters) {

        public String describe() {
            return String.format("%s 至 %s 停留约 %d 分钟（位移约 %d 米）",
                    from, to, durationMinutes, distanceMeters);
        }
    }
}
