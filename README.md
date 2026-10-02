# 城市餐厨废油回收预约称重联单合规追踪服务

覆盖 **预约 → 派单 → 到店扫码核验 → 称重 → 封桶 → 运输轨迹 → 处置厂二次称重 → 合规闭环 → 结算** 的全链路，
以 **电子联单为单一口径**（司机、商户、处置厂、监管端读取同一份数据），
并处理取消、迟到、混入厨余、设备离线、手动补录、轨迹缺失、**拒收责任追溯与返运**、重量质疑、监管抽查等异常，
对长期产量/营业规模/回收频次不匹配的门店自动生成风险名单。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Kafka · PostgreSQL 15 · Kafka 3.8（KRaft）· Flyway · Maven

架构要点：

- **电子联单（manifests）单一口径**：派单时创建联单，称重/收料/结算在同一数据库事务内回写联单，
  各方通过 `GET /api/manifests/{id}` 看到完全相同的全链路数据，不存在各端各存一套口径的问题。
- **Kafka 领域事件总线**：8 个主题承载全部状态变更事件，审计消费者将事件落库 `audit_logs`
  形成不可抵赖的审计流水，支撑监管追溯。
- **合规闭环引擎**：收料时比对出店重量、到厂重量、运输损耗率、油桶数量、轨迹完整性，
  阻断性异常（损耗超标/桶数不符/轨迹缺失）挂起联单，工单处理完毕后自动重估闭环。
- **拒收责任追溯**：处置厂因混入厨余残渣拒收后，系统自动固化商户交接照片、司机封桶记录、
  运输轨迹（含异常停留检测）、到厂封条核验与检测结果四类证据，给出责任建议
  （商户源头 / 运输污染 / 证据不足）；回收公司认定责任方与额外费用来源，
  返运批次记录返运路线、临时存放点、二次处理结果，结算单明确"谁的责任、费用从哪来"，
  杜绝处置厂、司机、商户互相推责。
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

## 测试账号（轻量鉴权：请求头 `X-Role` + `X-Account-Id`）

| 角色 | X-Role | X-Account-Id | 说明 |
|---|---|---|---|
| 商户 | `MERCHANT` | `merchant-1`（川香餐饮集团）/ `merchant-2`（老城美食广场） | 提交预约、取消、质疑重量、查回收证明与结算 |
| 回收公司 | `RECYCLER` | `recycler-1` | 派单、处理异常工单、查车辆效率 |
| 司机 | `DRIVER` | `driver-1`（张师傅）/ `driver-2`（李师傅） | 出发/到店/扫码核验/称重/轨迹/到厂 |
| 处置厂 | `PLANT` | `plant-1`（绿源废油处置厂） | 二次称重收料、接收/拒收 |
| 监管 | `REGULATOR` | `regulator-1` | 联单追溯、抽查、风险名单、趋势报表 |

演示基础数据（Flyway `V2__seed.sql`）：门店 3 家（含商圈与营业规模）、油桶 11 只
（`BAR-001`~`BAR-202`，V3 补充 `BAR-103`~`BAR-105` 用于拒收责任场景）、
车辆 2 台、司机 2 名、处置厂 1 家（接收时间 08:00-18:00）、称重设备 3 台（`DEV-001`~`DEV-003`）、
春熙路商圈早晚高峰限行规则。

## 验证方式

### 1. 自动化测试（38 个用例）

```bash
mvn test
```

> 集成测试需要本地 PostgreSQL（5433 端口，首次执行 `bash scripts/start-local.sh` 会自动创建 `wasteoil_test` 库）；Kafka 由测试内嵌启动，无需外部依赖。

- 单元测试（24 个）：派单规划（容量/限行/时间窗）、合规闭环比对（损耗/桶数/轨迹/质检）、
  拒收责任分析（封桶封条/异常停留/未封桶不定责，9 个）、风险分析
- 集成测试 `EndToEndIntegrationTest`（14 个）：真实 PostgreSQL 测试库 + 内嵌 Kafka + MockMvc，
  覆盖全链路闭环、一桶一码追溯、重量质疑、监管抽查、商户取消、手动补录、拒收、
  **商户源头混入（封桶→封条完好→商户担费→返运→二次处理）**、
  **运输污染（封条破损+异常停留→车队担费→无需返运）**、
  损耗异常阻断与恢复、派单校验、角色鉴权、风险扫描、三类报表

### 2. 端到端冒烟脚本（49 项断言，需应用已启动）

```bash
bash scripts/smoke.sh
```

按真实调用顺序走通：主流程闭环（联单 CLOSED、损耗 2kg、结算 947.2 元）→ 油桶追溯 →
商户质疑（结算冻结→按 297kg 调整为 950.4 元）→ 临时取消（派单/联单联动取消）→
手动补录（无原因 400，有原因留痕）→ 拒收（联单 REJECTED，自动发起责任调查）→
**商户源头责任（封桶→封条完好→商户承担 300 元处理费→返运→临时存放→二次接收 92kg→闭环）** →
**运输污染责任（封条破损+异常停留→车队承担 260 元→无需返运闭环）** →
损耗 20% 阻断闭环（工单处理后恢复 CLOSED）→
抽查/风险名单/车辆效率/回收证明/监管趋势 → Kafka 事件审计流水（含拒收责任事件）落库校验。

### 实际验证结论（本次交付前执行）

- `mvn test`：**38/38 通过**（单元 24 + 集成 14，集成测试使用 EmbeddedKafka + PostgreSQL 15 全新测试库，Flyway V1→V3 迁移成功）
- `bash scripts/smoke.sh`：**49/49 通过**（真实 Kafka 3.8 KRaft + PostgreSQL 15 + 应用实跑，全新库重跑）
- 冒烟期间应用日志 0 条 ERROR；8 个 Kafka 主题全部创建（新增 `wasteoil.reject.events`）；
  `audit_logs` 审计流水随事件持续增长，拒收事件链
  （调查发起×3、责任认定×2、返运离厂/入临时存放点/二次处理完成）全部可查
- 拒收费用结算单落库核对：商户源头单 `extra_fee=300 / fee_payer=MERCHANT`，
  运输污染单 `extra_fee=260 / fee_payer=DRIVER_FLEET`，`fee_source` 均写明费用来源

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
| POST | `/api/dispatches/{id}/seals` | 司机 | 装车称重后逐桶加封一次性封条（封桶记录，拒收定责关键证据） |
| POST | `/api/dispatches/{id}/load-complete` | 司机 | 装车完成起运（联单转 IN_TRANSIT） |
| POST | `/api/tracks/{dispatchId}` | 司机 | 批量上传运输轨迹点 |
| POST | `/api/receipts` | 处置厂 | 收料：二次称重 + 质检 + 接收/拒收（拒收须核验封条是否完好，自动发起责任调查） |
| GET | `/api/manifests/{id}` | 各端 | 联单全链路详情（单一口径：预约/派单/称重/轨迹/收料/结算/异常/拒收调查与返运） |
| GET | `/api/barrels/{code}/trace` | 各端 | 一桶一码追溯 |
| GET | `/api/rejects/by-manifest/{id}` | 各端 | 拒收责任调查：证据快照、系统责任建议、认定结论 |
| POST | `/api/rejects/by-manifest/{id}/confirm` | 回收公司 | 认定责任方（商户/司机车队）、额外处理费与费用来源（可认定无需返运） |
| POST | `/api/rejects/by-manifest/{id}/return` | 回收公司/司机 | 拒收批次返运离厂：登记返运路线、车辆、司机 |
| POST | `/api/rejects/by-manifest/{id}/storage` | 回收公司/司机 | 返运批次到达临时存放点 |
| POST | `/api/rejects/by-manifest/{id}/reprocess` | 回收公司 | 登记二次处理结果（分拣后接收/无害化处理/退回商户），联单闭环 |
| GET | `/api/rejects/by-manifest/{id}/return` | 各端 | 查询返运路线、临时存放点与二次处理结果 |
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
| 处置厂拒收 | 联单 `REJECTED`，登记 `PLANT_REJECT`，系统自动发起拒收责任调查 |
| 拒收责任-商户源头 | 全部油桶封条完好到厂仍混入厨余 → 建议 `MERCHANT_SOURCE`，回收公司认定后商户承担额外处理费，结算单（`REJECT_FEE`）明确费用来源 |
| 拒收责任-运输污染 | 封条破损/缺失或轨迹异常停留 → 建议 `TRANSPORT_CONTAMINATION`，司机和车队承担额外处理费 |
| 拒收批次返运 | 记录返运路线、临时存放点（含照片）、二次处理结果（分拣后接收/无害化处理/退回商户），完成后联单闭环；无需返运的认定后直接闭环 |
| 商户质疑重量 | 联单 `DISPUTED`、结算冻结，协商后按调整重量重新结算并闭环 |
| 监管抽查 | 抽查登记，不合格自动登记异常并将门店列入风险名单 |
| 长期产量不匹配 | 每日定时 + 手动扫描，90 天窗口产量/规模/频次比对，生成风险名单 |

## 目录结构

```
src/main/java/com/wasteoil/
├── appointment/   预约提交与取消
├── dispatch/      派单规划（容量/限行/时间窗）、运输执行与封桶
├── weighing/      扫码核验、设备/手动称重、设备心跳
├── track/         运输轨迹与缺失扫描
├── plant/         处置厂二次称重收料（拒收封条核验）
├── compliance/    合规闭环比对引擎
├── reject/        拒收责任追溯（照片/封桶/轨迹/检测证据快照）、责任认定、返运与二次处理
├── manifest/      电子联单（单一口径）与全链路详情
├── settlement/    结算（收购款联单/拒收费用单，费用来源留痕）
├── exception/     异常工单（阻断/非阻断）
├── dispute/       商户重量质疑
├── inspection/    监管抽查
├── risk/          长期趋势风险分析与风险名单
├── report/        监管趋势/车辆效率/回收证明
├── audit+ kafka/  领域事件总线与审计流水落库
└── merchant|barrel/ 基础数据与一桶一码追溯
```
