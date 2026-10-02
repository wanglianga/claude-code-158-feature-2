# 城市餐厨废油回收预约称重联单合规追踪服务

覆盖 **预约 → 派单 → 到店扫码核验 → 称重 → 封桶 → 运输轨迹 → 处置厂二次称重 → 合规闭环 → 结算** 的全链路，
以 **电子联单为单一口径**（司机、商户、处置厂、监管端读取同一份数据），
并处理取消、迟到、混入厨余、设备离线、手动补录、轨迹缺失、拒收、重量质疑、监管抽查等异常，
对长期产量/营业规模/回收频次不匹配的门店自动生成风险名单。
**处置厂拒收后自动建立责任认定案**：追溯商户交接照片、司机封桶记录、运输轨迹与到厂检测结果划分责任
（商户源头混入 → 商户承担额外处理费；运输中污染 → 司机与车队承担），
拒收批次返运全程记录返运路线、临时存放点与二次处理结果，结算费用单明确费用来源，避免互相推责。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Kafka · PostgreSQL 15 · Kafka 3.8（KRaft）· Flyway · Maven

架构要点：

- **电子联单（manifests）单一口径**：派单时创建联单，称重/收料/结算在同一数据库事务内回写联单，
  各方通过 `GET /api/manifests/{id}` 看到完全相同的全链路数据，不存在各端各存一套口径的问题。
- **Kafka 领域事件总线**：8 个主题承载全部状态变更事件，审计消费者将事件落库 `audit_logs`
  形成不可抵赖的审计流水，支撑监管追溯。
- **合规闭环引擎**：收料时比对出店重量、到厂重量、运输损耗率、油桶数量、轨迹完整性，
  阻断性异常（损耗超标/桶数不符/轨迹缺失）挂起联单，工单处理完毕后自动重估闭环。
- **拒收责任判断**：拒收即建案并固化证据快照（交接照片/封桶记录/轨迹/到厂检测），
  规则引擎给出责任建议，回收公司定责后自动生成费用来源明确的结算费用单；
  返运路线、临时存放点、二次处理结果全程留痕。
- **风险分析**：按 90 天窗口比对门店实际回收量与按营业规模预估的产量，长期不匹配进入风险名单。

## 本地启动

### 方式 A：一键脚本（无 Docker 环境，本仓库验证环境即此方式）

```bash
bash scripts/start-local.sh   # 启动 PostgreSQL(5433) + Kafka(9092) + 应用(8080)
```

### 方式 B：Docker（有 Docker 的开发机）

```bash
docker compose up -d          # PostgreSQL(映射到 5433) + Kafka(9092)
mvn spring-boot:run           # 应用(8080)，Flyway 自动建表并写入演示数据
```

> 数据库连接等配置见 `src/main/resources/application.yml`（默认 `localhost:5433/wasteoil`，账号 `wasteoil/wasteoil123`）。

### API 文档（Swagger UI）

应用启动后访问 **http://localhost:8080/swagger-ui/index.html**（OpenAPI 3.x JSON: `/v3/api-docs`），
可在线查看并调试全部 46 个端点（含拒收责任判断 `/api/rejections/**`、封桶 `/api/dispatches/{id}/seals`、
费用单 `/api/settlements/fees`）。调用时在请求头携带 `X-Role` 与 `X-Account-Id`。

### 重复验证

`mvn test` 可重复执行（集成测试每轮自动重置测试库业务数据）。
冒烟脚本重复执行前需先重置主库业务数据：

```bash
bash scripts/reset-db.sh    # 清空业务数据，油桶/设备恢复种子状态
bash scripts/smoke.sh
```

## 测试账号（轻量鉴权：请求头 `X-Role` + `X-Account-Id`）

| 角色 | X-Role | X-Account-Id | 说明 |
|---|---|---|---|
| 商户 | `MERCHANT` | `merchant-1`（川香餐饮集团）/ `merchant-2`（老城美食广场） | 提交预约、取消、质疑重量、查回收证明与结算 |
| 回收公司 | `RECYCLER` | `recycler-1` | 派单、处理异常工单、拒收定责与返运安排、查车辆效率 |
| 司机 | `DRIVER` | `driver-1`（张师傅）/ `driver-2`（李师傅） | 出发/到店/扫码核验/称重/封桶/轨迹/到厂 |
| 处置厂 | `PLANT` | `plant-1`（绿源废油处置厂） | 二次称重收料、接收/拒收 |
| 监管 | `REGULATOR` | `regulator-1` | 联单追溯、抽查、风险名单、趋势报表 |

演示基础数据（Flyway `V2__seed.sql`）：门店 3 家（含商圈与营业规模）、油桶 8 只（`BAR-001`~`BAR-202`）、
车辆 2 台、司机 2 名、处置厂 1 家（接收时间 08:00-18:00）、称重设备 3 台（`DEV-001`~`DEV-003`）、
春熙路商圈早晚高峰限行规则。

## 验证方式

### 1. 自动化测试（35 个用例）

```bash
mvn test
```

> 集成测试需要本地 PostgreSQL（5433 端口，首次执行 `bash scripts/start-local.sh` 会自动创建 `wasteoil_test` 库）；Kafka 由测试内嵌启动，无需外部依赖。

- 单元测试：派单规划（容量/限行/接收时间窗）、合规闭环比对（损耗/桶数/轨迹/质检）、风险分析、
  拒收责任建议规则（封条完好→商户源头，封条破损/轨迹缺失/未封桶→运输担责）（21 个）
- 集成测试 `EndToEndIntegrationTest`：真实 PostgreSQL 测试库 + 内嵌 Kafka + MockMvc，
  覆盖全链路闭环、一桶一码追溯、重量质疑、监管抽查、商户取消、手动补录、
  拒收责任判断（商户源头混入收费+返运二次处理、运输污染车队担责）、
  损耗异常阻断与恢复、派单校验、角色鉴权、风险扫描、三类报表（14 个）

### 2. 端到端冒烟脚本（47 项断言，需应用已启动）

```bash
bash scripts/smoke.sh
```

按真实调用顺序走通：主流程闭环（联单 CLOSED、损耗 2kg、结算 947.2 元）→ 油桶追溯 →
商户质疑（结算冻结→按 297kg 调整为 950.4 元）→ 临时取消（派单/联单联动取消）→
手动补录（无原因 400，有原因留痕）→ 拒收责任判断（封桶留证 → 拒收建案 → 证据快照 →
定责商户源头混入并收取 200 元额外处理费 → 返运路线/临时存放点 → 二次处理 → 结案联动工单；
未封桶批次定责运输污染、司机车队承担 300 元）→ 损耗 20% 阻断闭环（工单处理后恢复 CLOSED）→
抽查/风险名单/车辆效率/回收证明/监管趋势 → Kafka 事件审计流水落库校验。

### 实际验证结论（本次交付前执行）

- `mvn test`：**35/35 通过**（单元 21 + 集成 14，集成测试使用 EmbeddedKafka + PostgreSQL 测试库）
- `bash scripts/smoke.sh`：**47/47 通过**（真实 Kafka 3.8 KRaft + PostgreSQL 15 + 应用实跑）
- 冒烟期间应用日志 0 条 ERROR；8 个 Kafka 主题全部创建（含 `wasteoil.rejection.events`）；
  `audit_logs` 审计流水随事件持续增长；拒收责任案、额外费用单、封桶记录均落库可查

## 主要 API

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/appointments` | 商户 | 提交回收预约（门店/桶数/预计重量/存放位置/营业时段/联系人） |
| POST | `/api/appointments/{id}/cancel` | 商户 | 临时取消（联动取消派单与联单，登记异常） |
| POST | `/api/dispatches` | 回收公司 | 生成派单（校验车辆容量、商圈限行、处置厂接收时间窗，同时创建电子联单） |
| POST | `/api/dispatches/{id}/depart` `/arrive` | 司机 | 出发 / 到店（超时自动登记司机迟到） |
| POST | `/api/dispatches/{id}/verify` | 司机 | 扫码核验门店二维码、称重设备、交接人、现场照片 |
| POST | `/api/weighings` | 司机 | 称重（`DEVICE` 直传 / `MANUAL` 手动补录须填原因；离线设备数据打标） |
| POST | `/api/weighings/devices/{code}/heartbeat` | 设备 | 称重设备心跳 |
| POST | `/api/dispatches/{id}/seals` | 司机 | 封桶登记（封条编号+加封照片），拒收定责关键证据 |
| GET | `/api/dispatches/{id}/seals` | 各端 | 本趟封桶记录查询 |
| POST | `/api/dispatches/{id}/load-complete` | 司机 | 装车完成起运（联单转 IN_TRANSIT） |
| POST | `/api/tracks/{dispatchId}` | 司机 | 批量上传运输轨迹点 |
| POST | `/api/receipts` | 处置厂 | 收料：二次称重 + 质检 + 封条核验 + 接收/拒收，自动触发合规比对；拒收自动建责任认定案 |
| GET | `/api/rejections` `/api/rejections/{id}` `/api/rejections/by-manifest/{id}` | 各端 | 拒收责任认定案查询（含证据快照：交接照片/封桶记录/轨迹/到厂检测） |
| POST | `/api/rejections/{id}/determine` | 回收公司 | 责任认定：商户源头混入→商户担费；运输中污染→司机与车队担费 |
| POST | `/api/rejections/{id}/return` `/reprocess` `/close` | 回收公司 | 返运登记（路线+临时存放点）→ 二次处理结果 → 结案（联动处理拒收工单） |
| GET | `/api/settlements/fees` | 商户/回收公司/监管 | 额外费用单查询（费用来源留痕，避免推责） |
| GET | `/api/manifests/{id}` | 各端 | 联单全链路详情（单一口径：预约/派单/称重/轨迹/收料/结算/异常） |
| GET | `/api/barrels/{code}/trace` | 各端 | 一桶一码追溯 |
| POST | `/api/disputes` `/api/disputes/{id}/resolve` | 商户/回收公司 | 重量质疑与协商解决（结算冻结/调整） |
| GET/POST | `/api/exceptions` `/api/exceptions/{id}/resolve` | 回收公司/监管 | 异常工单查询与处理（阻断类处理后联单自动重估） |
| POST | `/api/inspections` | 监管 | 抽查登记（不合格自动入风险名单） |
| POST/GET | `/api/risks/scan` `/api/risks` | 监管 | 风险扫描与风险名单 |
| GET | `/api/reports/regulator/trend` | 监管 | 废油流向合规处置的长期趋势 |
| GET | `/api/reports/vehicle-efficiency` | 回收公司 | 车辆效率（趟次/运量/准点） |
| GET | `/api/reports/stores/{id}/certificate` | 商户 | 回收证明（联单清单 + 合计 + 结算金额） |
| GET | `/api/settlements?storeId=` | 商户 | 结算单查询 |

## 异常场景处理一览

| 场景 | 机制 |
|---|---|
| 商户临时取消 | 预约/派单/联单联动取消，登记 `MERCHANT_CANCEL` 工单 |
| 司机迟到 | 到店超时判定 + 每分钟定时扫描，登记 `DRIVER_LATE` |
| 油桶混入厨余 | 收料质检 `CONTAMINATED`，联单标记 `qualityIssue` 并登记工单（不阻断闭环） |
| 称重设备离线 | 心跳超时判定离线，离线数据在联单上计数留痕，登记 `DEVICE_OFFLINE` |
| 司机手动补录 | 必须填写原因，联单记录补录条数，登记 `MANUAL_ENTRY` |
| 运输轨迹缺失 | 定时扫描 + 收料时合规校验，阻断闭环直至工单处理 |
| 处置厂拒收 | 联单 `REJECTED`，自动建立责任认定案：证据快照（交接照片/封桶记录/轨迹/到厂检测）→ 规则建议责任方 → 回收公司定责 → 额外处理费计入结算费用单（商户源头→商户承担，运输污染→司机与车队承担）；返运记录路线/临时存放点/二次处理结果，结案联动处理工单 |
| 商户质疑重量 | 联单 `DISPUTED`、结算冻结，协商后按调整重量重新结算并闭环 |
| 监管抽查 | 抽查登记，不合格自动登记异常并将门店列入风险名单 |
| 长期产量不匹配 | 每日定时 + 手动扫描，90 天窗口产量/规模/频次比对，生成风险名单 |

## 目录结构

```
src/main/java/com/wasteoil/
├── appointment/   预约提交与取消
├── dispatch/      派单规划（容量/限行/时间窗）、运输执行与司机封桶
├── weighing/      扫码核验、设备/手动称重、设备心跳
├── track/         运输轨迹与缺失扫描
├── plant/         处置厂二次称重收料（含封条核验）
├── compliance/    合规闭环比对引擎
├── manifest/      电子联单（单一口径）与全链路详情
├── settlement/    结算（联单为唯一结算依据）与额外费用单
├── rejection/     拒收责任认定（证据快照/定责/返运/二次处理）
├── exception/     异常工单（阻断/非阻断）
├── dispute/       商户重量质疑
├── inspection/    监管抽查
├── risk/          长期趋势风险分析与风险名单
├── report/        监管趋势/车辆效率/回收证明
├── audit+ kafka/  领域事件总线与审计流水落库
└── merchant|barrel/ 基础数据与一桶一码追溯
```
