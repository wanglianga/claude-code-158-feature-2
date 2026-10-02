-- 处置厂拒收后的责任判断、返运与费用来源
-- 场景：处置厂发现废油混入大量厨余残渣并拒收，需要追溯
--      商户交接照片 / 司机封桶记录 / 运输轨迹 / 到厂检测结果，
--      判定责任属于商户源头还是运输环节，并记录返运路线、临时存放点、二次处理结果，
--      结算单明确额外费用与费用来源，避免处置厂、司机、商户互相推责。

-- ============ 司机封桶记录（装车称重后逐桶加封一次性封条） ============
CREATE TABLE barrel_seal_records (
    id          BIGSERIAL PRIMARY KEY,
    dispatch_id BIGINT       NOT NULL REFERENCES dispatch_orders (id),
    barrel_id   BIGINT       NOT NULL REFERENCES barrels (id),
    seal_code   VARCHAR(64)  NOT NULL,              -- 一次性封条编号
    sealed_by   VARCHAR(64)  NOT NULL,              -- 封桶司机
    photo_url   VARCHAR(512),                       -- 封桶现场照片
    note        VARCHAR(512),
    sealed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (dispatch_id, barrel_id)                 -- 同一趟同一桶只封一次
);
CREATE INDEX idx_seal_dispatch ON barrel_seal_records (dispatch_id);

-- ============ 拒收责任调查（证据快照 + 责任认定 + 费用来源） ============
CREATE TABLE reject_investigations (
    id                     BIGSERIAL PRIMARY KEY,
    manifest_id            BIGINT       NOT NULL UNIQUE REFERENCES manifests (id),
    dispatch_id            BIGINT       NOT NULL REFERENCES dispatch_orders (id),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'OPEN',   -- OPEN / CONFIRMED
    -- 追溯证据快照（拒收发起鉴定时固化，事后原始数据变更不影响定责依据）
    handover_photo_count   INT          NOT NULL DEFAULT 0,       -- 商户交接照片数（扫码核验）
    weighing_photo_count   INT          NOT NULL DEFAULT 0,       -- 称重现场照片数
    sealed_barrel_count    INT          NOT NULL DEFAULT 0,       -- 已加封封条的桶数
    total_barrel_count     INT          NOT NULL DEFAULT 0,       -- 本趟装车桶数
    all_sealed             BOOLEAN      NOT NULL DEFAULT FALSE,  -- 是否全部桶已封桶
    seal_intact_at_plant   BOOLEAN,                               -- 到厂封条是否完好（处置厂核验）
    plant_check_detail     VARCHAR(1024),                         -- 到厂检测结果描述
    track_point_count      INT          NOT NULL DEFAULT 0,       -- 运输轨迹点数
    abnormal_stop_count    INT          NOT NULL DEFAULT 0,       -- 运输途中异常停留次数
    abnormal_stop_detail   VARCHAR(1024),                         -- 异常停留明细
    contamination_note     VARCHAR(1024),                         -- 厨余残渣等污染情况
    -- 系统基于证据给出的责任建议（回收公司可结合人工复核覆盖）
    suggested_liability    VARCHAR(32),   -- MERCHANT_SOURCE / TRANSPORT_CONTAMINATION / INCONCLUSIVE
    suggested_reason       VARCHAR(1024),
    -- 回收公司认定结论
    liability_party        VARCHAR(32),   -- MERCHANT（商户源头）/ DRIVER_FLEET（司机车队）/ NONE（无法认定）
    extra_fee              NUMERIC(12, 2),                         -- 额外处理费用（元）
    fee_payer              VARCHAR(32),   -- MERCHANT / DRIVER_FLEET
    fee_source             VARCHAR(1024), -- 费用来源说明（什么费用、为何由该方承担）
    no_return_required     BOOLEAN       NOT NULL DEFAULT FALSE,  -- 无需返运（如厂内移交无害化处理）
    decided_by             VARCHAR(64),
    decided_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ============ 拒收批次返运（路线 / 临时存放点 / 二次处理结果） ============
CREATE TABLE reject_returns (
    id                   BIGSERIAL PRIMARY KEY,
    manifest_id          BIGINT        NOT NULL UNIQUE REFERENCES manifests (id),
    investigation_id     BIGINT        NOT NULL REFERENCES reject_investigations (id),
    -- 返运路线：处置厂 → 临时存放点/二次处理单位
    return_route         VARCHAR(1024) NOT NULL,
    vehicle_id           BIGINT        REFERENCES vehicles (id),
    driver_id            BIGINT        REFERENCES drivers (id),
    returned_by          VARCHAR(64)   NOT NULL,
    departed_plant_at    TIMESTAMPTZ,
    -- 临时存放点
    storage_point        VARCHAR(256),
    storage_arrived_at   TIMESTAMPTZ,
    storage_photo_url    VARCHAR(512),
    -- 二次处理结果
    reprocess_result     VARCHAR(32),   -- RE_ACCEPTED（分拣后合规接收）/ DISPOSED（无害化处理）/ RETURNED_MERCHANT（退回商户）
    reprocess_weight_kg  NUMERIC(12, 2),
    reprocess_note       VARCHAR(1024),
    reprocessed_at       TIMESTAMPTZ,
    status               VARCHAR(16)   NOT NULL DEFAULT 'DEPARTED', -- DEPARTED / IN_STORAGE / REPROCESSED
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ============ 处置厂收料扩展：到厂封条核验结果 ============
ALTER TABLE plant_receipts ADD COLUMN seal_intact BOOLEAN;            -- 到厂时封条是否完好（拒收时由处置厂核验）
ALTER TABLE plant_receipts ADD COLUMN seal_note   VARCHAR(512);       -- 封条异常说明（破损/缺失封条编号等）

-- ============ 结算单扩展：拒收额外费用与费用来源 ============
ALTER TABLE settlements ADD COLUMN bill_type        VARCHAR(16) NOT NULL DEFAULT 'PURCHASE'; -- PURCHASE 购油款 / REJECT_FEE 拒收费用单
ALTER TABLE settlements ADD COLUMN extra_fee        NUMERIC(12, 2) NOT NULL DEFAULT 0;      -- 拒收产生的额外处理费用
ALTER TABLE settlements ADD COLUMN fee_payer        VARCHAR(32);                              -- MERCHANT / DRIVER_FLEET
ALTER TABLE settlements ADD COLUMN fee_source       VARCHAR(1024);                           -- 费用来源说明
ALTER TABLE settlements ADD COLUMN investigation_id BIGINT REFERENCES reject_investigations (id);

-- ============ 补充演示油桶（拒收责任追溯场景：金融城店） ============
INSERT INTO barrels (id, code, capacity_kg, store_id, status) VALUES
    (21, 'BAR-103', 200, 2, 'AT_STORE'),
    (22, 'BAR-104', 200, 2, 'AT_STORE'),
    (23, 'BAR-105', 200, 2, 'AT_STORE');
SELECT setval('barrels_id_seq', 30);
