package com.wasteoil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasteoil.audit.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：真实 PostgreSQL（测试库）+ 内嵌 Kafka + MockMvc。
 * 覆盖主流程闭环与各类异常场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    // 跨用例共享的主流程产物
    static long happyManifestId;
    static long happyDispatchId;

    /**
     * 清理上一轮运行遗留的业务数据并恢复种子状态（油桶回门店、设备在线），
     * 保证测试在持久化测试库上可以重复执行。
     */
    @BeforeAll
    void resetDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE settlement_fees, rejection_cases, barrel_seals, settlements,
                         inspections, disputes, exception_tickets, plant_receipts,
                         track_points, pickup_verifications, weighing_records,
                         manifests, dispatch_orders, appointments, risk_entries, audit_logs
                RESTART IDENTITY CASCADE
                """);
        jdbcTemplate.update("UPDATE barrels SET status = 'AT_STORE'");
        jdbcTemplate.update("UPDATE weighing_devices SET status = 'ONLINE', last_heartbeat_at = now()");
    }

    // ---------- 工具方法 ----------

    private JsonNode postJson(String url, String role, String account, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url)
                        .header("X-Role", role)
                        .header("X-Account-Id", account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String url, String role, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url).header("X-Role", role).header("X-Account-Id", "test"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String tomorrowAt(int hour, int minute) {
        return LocalDate.now(ZONE).plusDays(1).atTime(LocalTime.of(hour, minute))
                .atZone(ZONE).toInstant().toString();
    }

    private long createAppointment(long storeId, int barrels, String estimatedWeight) throws Exception {
        JsonNode resp = postJson("/api/appointments", "MERCHANT", "merchant-1", """
                {"storeId": %d, "barrelCount": %d, "estimatedWeightKg": %s,
                 "windowStart": "%s", "windowEnd": "%s"}
                """.formatted(storeId, barrels, estimatedWeight, tomorrowAt(10, 0), tomorrowAt(11, 0)), 200);
        return resp.path("data").path("id").asLong();
    }

    private long planDispatch(long appointmentId, String start, String end) throws Exception {
        JsonNode resp = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s", "routeNote": "测试路线"}
                """.formatted(appointmentId, start, end), 200);
        return resp.path("data").path("id").asLong();
    }

    private long manifestIdOf(long dispatchId) throws Exception {
        JsonNode resp = getJson("/api/manifests", "REGULATOR", 200);
        for (JsonNode m : resp.path("data")) {
            if (m.path("dispatchId").asLong() == dispatchId) {
                return m.path("id").asLong();
            }
        }
        throw new IllegalStateException("未找到派单 " + dispatchId + " 对应的联单");
    }

    /** 司机执行到店核验 */
    private void arriveAndVerify(long dispatchId, String storeQr, String deviceCode) throws Exception {
        postJson("/api/dispatches/" + dispatchId + "/depart", "DRIVER", "driver-1", "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive", "DRIVER", "driver-1", "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/verify", "DRIVER", "driver-1", """
                {"storeQr": "%s", "deviceCode": "%s", "handoverName": "王店长",
                 "photoUrls": "https://img.example.com/p1.jpg"}
                """.formatted(storeQr, deviceCode), 200);
    }

    private void weigh(long dispatchId, String barrelCode, String weight, String source, String note) throws Exception {
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "%s", "weightKg": %s, "deviceCode": "DEV-001",
                 "source": "%s", "photoUrl": "https://img.example.com/w.jpg",
                 "handoverName": "王店长", "note": %s}
                """.formatted(dispatchId, barrelCode, weight, source,
                note == null ? "null" : "\"" + note + "\""), 200);
    }

    private void uploadTracksAndArrivePlant(long dispatchId) throws Exception {
        postJson("/api/tracks/" + dispatchId, "DRIVER", "driver-1", """
                {"points": [{"lat": 30.66, "lng": 104.06, "recordedAt": "%s"},
                            {"lat": 30.60, "lng": 104.10, "recordedAt": "%s"}]}
                """.formatted(Instant.now(), Instant.now()), 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive-plant", "DRIVER", "driver-1", "{}", 200);
    }

    // ---------- 主流程 ----------

    @Test
    @Order(1)
    void 全链路闭环_预约到结算() throws Exception {
        // 设备心跳
        postJson("/api/weighings/devices/DEV-001/heartbeat", "RECYCLER", "recycler-1", "{}", 200);

        long appointmentId = createAppointment(1, 2, "300");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        happyDispatchId = dispatchId;

        arriveAndVerify(dispatchId, "STORE-QR-1", "DEV-001");
        weigh(dispatchId, "BAR-001", "150", "DEVICE", null);
        weigh(dispatchId, "BAR-002", "148", "DEVICE", null);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);

        long manifestId = manifestIdOf(dispatchId);
        happyManifestId = manifestId;

        // 处置厂二次称重 296kg（损耗 2kg，约 0.67%）
        JsonNode receipt = postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 296, "barrelCount": 2,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);
        assertThat(receipt.path("data").path("decision").asText()).isEqualTo("ACCEPT");

        // 联单应闭环：CLOSED，损耗已计算
        JsonNode detail = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        JsonNode manifest = detail.path("data").path("manifest");
        assertThat(manifest.path("status").asText()).isEqualTo("CLOSED");
        assertThat(manifest.path("complianceResult").asText()).isEqualTo("PASS");
        assertThat(manifest.path("exitWeightKg").asDouble()).isEqualTo(298.0);
        assertThat(manifest.path("arrivalWeightKg").asDouble()).isEqualTo(296.0);
        assertThat(manifest.path("lossKg").asDouble()).isEqualTo(2.0);
        // 全链路数据齐备：称重 2 条、轨迹 2 条、收料 1 条、结算 1 条
        assertThat(detail.path("data").path("weighings").size()).isEqualTo(2);
        assertThat(detail.path("data").path("tracks").size()).isEqualTo(2);
        assertThat(detail.path("data").path("receipt").isNull()).isFalse();
        assertThat(detail.path("data").path("settlement").isNull()).isFalse();

        // 结算金额 = 296 * 3.20 = 947.20
        JsonNode settlement = getJson("/api/settlements/by-manifest/" + manifestId, "MERCHANT", 200);
        assertThat(settlement.path("data").path("amount").asDouble()).isEqualTo(947.20);
        assertThat(settlement.path("data").path("status").asText()).isEqualTo("CONFIRMED");

        // Kafka 事件应已被审计消费者落库
        await().atMost(15, TimeUnit.SECONDS).until(() -> auditLogRepository.findAll().stream()
                .anyMatch(a -> "ManifestClosed".equals(a.getEventType())));
    }

    @Test
    @Order(2)
    void 一桶一码追溯() throws Exception {
        JsonNode trace = getJson("/api/barrels/BAR-001/trace", "REGULATOR", 200);
        assertThat(trace.path("data").path("barrel").path("status").asText()).isEqualTo("AT_PLANT");
        assertThat(trace.path("data").path("weighingRecords").size()).isEqualTo(1);
        assertThat(trace.path("data").path("weighingRecords").get(0).path("weightKg").asDouble())
                .isEqualTo(150.0);
    }

    @Test
    @Order(3)
    void 商户质疑重量_冻结结算_协商调整() throws Exception {
        // 商户发起质疑
        JsonNode dispute = postJson("/api/disputes", "MERCHANT", "merchant-1", """
                {"manifestId": %d, "reason": "出店称重 298kg，到厂 296kg，对损耗 2kg 有异议"}
                """.formatted(happyManifestId), 200);
        long disputeId = dispute.path("data").path("id").asLong();

        // 联单进入 DISPUTED，结算冻结
        JsonNode manifest = getJson("/api/manifests/" + happyManifestId, "MERCHANT", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("DISPUTED");
        JsonNode settlement = getJson("/api/settlements/by-manifest/" + happyManifestId, "MERCHANT", 200);
        assertThat(settlement.path("data").path("status").asText()).isEqualTo("FROZEN");

        // 协商解决：按 297kg 结算
        postJson("/api/disputes/" + disputeId + "/resolve", "RECYCLER", "recycler-1", """
                {"resolution": "双方复核称重记录，按 297kg 结算", "adjustedWeightKg": 297}
                """, 200);
        JsonNode after = getJson("/api/settlements/by-manifest/" + happyManifestId, "MERCHANT", 200);
        assertThat(after.path("data").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(after.path("data").path("amount").asDouble()).isEqualTo(950.40);
        JsonNode closed = getJson("/api/manifests/" + happyManifestId, "MERCHANT", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    @Order(4)
    void 监管抽查不合格_登记异常并进风险名单() throws Exception {
        postJson("/api/inspections", "REGULATOR", "regulator-1", """
                {"manifestId": %d, "result": "FAIL", "note": "现场照片不清晰，交接记录不完整"}
                """.formatted(happyManifestId), 200);

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + happyManifestId,
                "REGULATOR", 200);
        boolean hasInspectionTicket = false;
        for (JsonNode t : tickets.path("data")) {
            if ("REGULATORY_INSPECTION".equals(t.path("type").asText())) {
                hasInspectionTicket = true;
            }
        }
        assertThat(hasInspectionTicket).isTrue();

        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        boolean hasRisk = false;
        for (JsonNode r : risks.path("data")) {
            if ("INSPECTION_FAIL".equals(r.path("indicator").asText())) {
                hasRisk = true;
            }
        }
        assertThat(hasRisk).isTrue();
    }

    // ---------- 异常场景 ----------

    @Test
    @Order(10)
    void 商户临时取消_联动取消派单与联单() throws Exception {
        long appointmentId = createAppointment(1, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/appointments/" + appointmentId + "/cancel", "MERCHANT", "merchant-1",
                "{\"reason\": \"今日店休，暂停回收\"}", 200);

        JsonNode appointment = getJson("/api/appointments/" + appointmentId, "MERCHANT", 200);
        assertThat(appointment.path("data").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode dispatch = getJson("/api/dispatches/" + dispatchId, "RECYCLER", 200);
        assertThat(dispatch.path("data").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("CANCELLED");

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=APPOINTMENT&refId=" + appointmentId,
                "RECYCLER", 200);
        assertThat(tickets.path("data").get(0).path("type").asText()).isEqualTo("MERCHANT_CANCEL");
    }

    @Test
    @Order(11)
    void 手动补录_必须填原因并留痕() throws Exception {
        long appointmentId = createAppointment(1, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-1", "DEV-001");

        // 无原因 → 400
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-003", "weightKg": 120, "source": "MANUAL"}
                """.formatted(dispatchId), 400);

        // 有原因 → 成功，联单记录手动补录条数
        weigh(dispatchId, "BAR-003", "120", "MANUAL", "设备故障，人工磅秤复核");
        long manifestId = manifestIdOf(dispatchId);
        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("manualWeighCount").asInt()).isEqualTo(1);

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean hasManual = false;
        for (JsonNode t : tickets.path("data")) {
            if ("MANUAL_ENTRY".equals(t.path("type").asText())) {
                hasManual = true;
            }
        }
        assertThat(hasManual).isTrue();
    }

    @Test
    @Order(12)
    void 处置厂拒收_联单进入拒收态() throws Exception {
        postJson("/api/weighings/devices/DEV-002/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(2, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-2", "DEV-002");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-101", "weightKg": 100, "deviceCode": "DEV-002",
                 "source": "DEVICE", "handoverName": "陈店长"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 100, "barrelCount": 1,
                 "qualityCheck": "CONTAMINATED", "decision": "REJECT",
                 "rejectReason": "油桶混入大量厨余垃圾，拒绝接收", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("REJECTED");
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean hasReject = false;
        for (JsonNode t : tickets.path("data")) {
            if ("PLANT_REJECT".equals(t.path("type").asText())) {
                hasReject = true;
            }
        }
        assertThat(hasReject).isTrue();
    }

    @Test
    @Order(13)
    void 运输损耗异常_阻断闭环_处理后恢复() throws Exception {
        postJson("/api/weighings/devices/DEV-002/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(2, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-2", "DEV-002");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-102", "weightKg": 100, "deviceCode": "DEV-002",
                 "source": "DEVICE", "handoverName": "陈店长"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        // 出店 100kg，到厂仅 80kg，损耗 20% 超阈值
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 80, "barrelCount": 1,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("EXCEPTION");
        assertThat(manifest.path("data").path("manifest").path("complianceResult").asText())
                .isEqualTo("EXCEPTION");

        // 处理阻断性异常工单后，联单自动重估并闭环
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "RECYCLER", 200);
        long ticketId = -1;
        for (JsonNode t : tickets.path("data")) {
            if ("TRANSPORT_LOSS_ABNORMAL".equals(t.path("type").asText())) {
                ticketId = t.path("id").asLong();
            }
        }
        assertThat(ticketId).isPositive();
        postJson("/api/exceptions/" + ticketId + "/resolve", "RECYCLER", "recycler-1",
                "{\"resolution\": \"确认为运输途中阀门渗漏，已维修并备案\"}", 200);

        JsonNode closed = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
    }

    // ---------- 拒收后责任判断 ----------

    @Test
    @Order(14)
    void 拒收定责_商户源头混入_收取额外处理费并返运() throws Exception {
        postJson("/api/weighings/devices/DEV-003/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(3, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-3", "DEV-003");

        // 未称重先封桶 → 409
        postJson("/api/dispatches/" + dispatchId + "/seals", "DRIVER", "driver-1", """
                {"barrelCode": "BAR-201", "sealNo": "SEAL-201-A"}
                """, 409);

        // 称重（留现场照片）→ 封桶（留加封照片）
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-201", "weightKg": 100, "deviceCode": "DEV-003",
                 "source": "DEVICE", "photoUrl": "https://img.example.com/w201.jpg", "handoverName": "刘老板"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/seals", "DRIVER", "driver-1", """
                {"barrelCode": "BAR-201", "sealNo": "SEAL-201-A", "photoUrl": "https://img.example.com/seal201.jpg"}
                """, 200);
        JsonNode seals = getJson("/api/dispatches/" + dispatchId + "/seals", "DRIVER", 200);
        assertThat(seals.path("data").size()).isEqualTo(1);

        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        // 处置厂拒收：混入厨余残渣，到厂封条完好
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 100, "barrelCount": 1,
                 "qualityCheck": "CONTAMINATED", "sealCheck": "INTACT", "decision": "REJECT",
                 "rejectReason": "混入大量厨余残渣", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        // 拒收案件自动建立：证据快照齐备（交接照片/封桶记录/轨迹/到厂检测），系统建议商户源头
        JsonNode caseResp = getJson("/api/rejections/by-manifest/" + manifestId, "REGULATOR", 200);
        JsonNode rejectionCase = caseResp.path("data").path("rejectionCase");
        long caseId = rejectionCase.path("id").asLong();
        assertThat(rejectionCase.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(rejectionCase.path("suggestedLiability").asText()).isEqualTo("MERCHANT_SOURCE");
        JsonNode evidence = caseResp.path("data").path("evidence");
        assertThat(evidence.path("weighedBarrelCount").asInt()).isEqualTo(1);
        assertThat(evidence.path("sealedBarrelCount").asInt()).isEqualTo(1);
        assertThat(evidence.path("trackPointCount").asInt()).isEqualTo(2);
        assertThat(evidence.path("arrivalSealCheck").asText()).isEqualTo("INTACT");
        assertThat(evidence.path("handoverPhotos").size()).isGreaterThanOrEqualTo(1);
        assertThat(evidence.path("sealRecords").get(0).path("sealNo").asText()).isEqualTo("SEAL-201-A");

        // 定责说明必填；商户无权定责
        postJson("/api/rejections/" + caseId + "/determine", "RECYCLER", "recycler-1", """
                {"liabilityParty": "MERCHANT_SOURCE", "extraFeeAmount": 200}
                """, 400);
        postJson("/api/rejections/" + caseId + "/determine", "MERCHANT", "merchant-1", """
                {"liabilityParty": "MERCHANT_SOURCE", "note": "x", "extraFeeAmount": 200}
                """, 403);

        // 定责：商户源头混入，回收公司收取额外处理费 200 元
        JsonNode determined = postJson("/api/rejections/" + caseId + "/determine", "RECYCLER", "recycler-1", """
                {"liabilityParty": "MERCHANT_SOURCE", "note": "封条完好且交接照片可见残渣，判定商户源头混入",
                 "extraFeeAmount": 200}
                """, 200);
        assertThat(determined.path("data").path("status").asText()).isEqualTo("DETERMINED");
        assertThat(determined.path("data").path("feeBearer").asText()).isEqualTo("MERCHANT");

        // 结算费用单：费用来源明确（哪起拒收、谁承担、为什么）
        JsonNode fees = getJson("/api/settlements/fees?manifestId=" + manifestId, "MERCHANT", 200);
        assertThat(fees.path("data").size()).isEqualTo(1);
        JsonNode fee = fees.path("data").get(0);
        assertThat(fee.path("bearer").asText()).isEqualTo("MERCHANT");
        assertThat(fee.path("amount").asDouble()).isEqualTo(200.0);
        assertThat(fee.path("sourceNote").asText()).contains("商户源头混入");

        // 联单详情可见拒收案件与费用（各端同一口径）
        JsonNode detail = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(detail.path("data").path("rejectionCase").path("id").asLong()).isEqualTo(caseId);
        assertThat(detail.path("data").path("settlementFees").size()).isEqualTo(1);

        // 返运：记录返运路线与临时存放点 → 二次处理结果 → 结案
        JsonNode returning = postJson("/api/rejections/" + caseId + "/return", "RECYCLER", "recycler-1", """
                {"returnRoute": "处置厂→经开区暂存点→二次处理车间", "tempStoragePoint": "经开区环保园暂存仓 A-3"}
                """, 200);
        assertThat(returning.path("data").path("status").asText()).isEqualTo("RETURNING");
        assertThat(returning.path("data").path("returnRoute").asText()).contains("暂存点");
        JsonNode reprocessed = postJson("/api/rejections/" + caseId + "/reprocess", "RECYCLER", "recycler-1", """
                {"secondaryResult": "厨余残渣分离后二次处置完成，产出工业原料油 82kg"}
                """, 200);
        assertThat(reprocessed.path("data").path("status").asText()).isEqualTo("REPROCESSED");
        JsonNode closed = postJson("/api/rejections/" + caseId + "/close", "RECYCLER", "recycler-1", "{}", 200);
        assertThat(closed.path("data").path("status").asText()).isEqualTo("CLOSED");

        // 结案后拒收异常工单联动处理完毕
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "RECYCLER", 200);
        boolean rejectResolved = false;
        for (JsonNode t : tickets.path("data")) {
            if ("PLANT_REJECT".equals(t.path("type").asText())
                    && "RESOLVED".equals(t.path("status").asText())) {
                rejectResolved = true;
            }
        }
        assertThat(rejectResolved).isTrue();
    }

    @Test
    @Order(15)
    void 拒收定责_运输中污染_司机车队担责() throws Exception {
        postJson("/api/weighings/devices/DEV-003/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(3, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-3", "DEV-003");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-202", "weightKg": 100, "deviceCode": "DEV-003",
                 "source": "DEVICE", "handoverName": "刘老板"}
                """.formatted(dispatchId), 200);
        // 封桶后到厂封条破损 → 运输环节被打开过
        postJson("/api/dispatches/" + dispatchId + "/seals", "DRIVER", "driver-1", """
                {"barrelCode": "BAR-202", "sealNo": "SEAL-202-A"}
                """, 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 100, "barrelCount": 1,
                 "qualityCheck": "CONTAMINATED", "sealCheck": "BROKEN", "decision": "REJECT",
                 "rejectReason": "封条破损，桶内混入厨余", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode caseResp = getJson("/api/rejections/by-manifest/" + manifestId, "REGULATOR", 200);
        long caseId = caseResp.path("data").path("rejectionCase").path("id").asLong();
        assertThat(caseResp.path("data").path("rejectionCase").path("suggestedLiability").asText())
                .isEqualTo("TRANSPORT");

        // 定责运输中污染：额外费用由司机与车队承担
        JsonNode determined = postJson("/api/rejections/" + caseId + "/determine", "RECYCLER", "recycler-1", """
                {"liabilityParty": "TRANSPORT", "note": "到厂封条破损，判定运输途中被开封污染",
                 "extraFeeAmount": 300}
                """, 200);
        assertThat(determined.path("data").path("feeBearer").asText()).isEqualTo("FLEET");

        JsonNode fees = getJson("/api/settlements/fees?manifestId=" + manifestId, "RECYCLER", 200);
        JsonNode fee = fees.path("data").get(0);
        assertThat(fee.path("bearer").asText()).isEqualTo("FLEET");
        assertThat(fee.path("bearerDriverId").asLong()).isEqualTo(1);
        assertThat(fee.path("bearerVehicleId").asLong()).isEqualTo(1);
        assertThat(fee.path("sourceNote").asText()).contains("运输途中污染");

        // 无需返运直接结案；结案后不能重复定责
        postJson("/api/rejections/" + caseId + "/close", "RECYCLER", "recycler-1", "{}", 200);
        postJson("/api/rejections/" + caseId + "/determine", "RECYCLER", "recycler-1", """
                {"liabilityParty": "TRANSPORT", "note": "重复定责"}
                """, 409);
    }

    // ---------- 校验与鉴权 ----------

    @Test
    @Order(20)
    void 派单校验_容量不足与限行冲突() throws Exception {
        // 车辆容量 5000，预约 6000 → 拒绝
        long bigAppointment = createAppointment(1, 1, "6000");
        postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(bigAppointment, tomorrowAt(10, 0), tomorrowAt(11, 0)), 400);

        // 春熙路商圈 17:00-19:30 限行 → 拒绝
        long appointment = createAppointment(1, 1, "100");
        JsonNode resp = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(appointment, tomorrowAt(17, 30), tomorrowAt(18, 0)), 400);
        assertThat(resp.path("message").asText()).contains("限行");
    }

    @Test
    @Order(21)
    void 角色鉴权_商户不能派单() throws Exception {
        postJson("/api/dispatches", "MERCHANT", "merchant-1", """
                {"appointmentId": 1, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(tomorrowAt(10, 0), tomorrowAt(11, 0)), 403);
    }

    // ---------- 报表与风险 ----------

    @Test
    @Order(30)
    void 风险扫描_长期不匹配的门店进入风险名单() throws Exception {
        postJson("/api/risks/scan", "REGULATOR", "regulator-1", "{}", 200);
        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        boolean yieldMismatch = false;
        for (JsonNode r : risks.path("data")) {
            if ("YIELD_MISMATCH".equals(r.path("indicator").asText())) {
                yieldMismatch = true;
            }
        }
        // 门店预期产量（60kg/天*90天）远高于实际回收量，应被识别
        assertThat(yieldMismatch).isTrue();
    }

    @Test
    @Order(31)
    void 报表_车辆效率与商户回收证明与监管趋势() throws Exception {
        JsonNode efficiency = getJson("/api/reports/vehicle-efficiency", "RECYCLER", 200);
        assertThat(efficiency.path("data").size()).isGreaterThanOrEqualTo(1);
        JsonNode first = efficiency.path("data").get(0);
        assertThat(first.path("plateNo").asText()).isNotBlank();

        JsonNode certificate = getJson("/api/reports/stores/1/certificate", "MERCHANT", 200);
        assertThat(certificate.path("data").path("manifestCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(certificate.path("data").path("totalWeightKg").asDouble()).isGreaterThan(0);

        JsonNode trend = getJson("/api/reports/regulator/trend", "REGULATOR", 200);
        assertThat(trend.path("data").size()).isGreaterThanOrEqualTo(1);
    }
}
