-- 拒收后责任判断：司机封桶记录、拒收责任认定单、结算额外费用

-- ============ 司机封桶记录（装车后逐桶加封，作为责任划分证据） ============
CREATE TABLE barrel_seals (
    id          BIGSERIAL PRIMARY KEY,
    dispatch_id BIGINT      NOT NULL REFERENCES dispatch_orders (id),
    barrel_id   BIGINT      NOT NULL REFERENCES barrels (id),
    seal_no     VARCHAR(64) NOT NULL,               -- 封条编号
    photo_url   VARCHAR(512),                       -- 加封照片
    sealed_by   VARCHAR(64) NOT NULL,               -- 加封人（司机）
    sealed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dispatch_id, barrel_id)                 -- 同一趟同一桶只加封一次
);
CREATE INDEX idx_seals_dispatch ON barrel_seals (dispatch_id);

-- 处置厂收料时核验封条状态：INTACT 完好 / BROKEN 破损 / UNKNOWN 未核验
ALTER TABLE plant_receipts ADD COLUMN seal_check VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

-- ============ 拒收责任认定单（处置厂拒收后自动建案，追溯定责） ============
CREATE TABLE rejection_cases (
    id                  BIGSERIAL PRIMARY KEY,
    manifest_id         BIGINT       NOT NULL UNIQUE REFERENCES manifests (id),
    receipt_id          BIGINT       NOT NULL REFERENCES plant_receipts (id),
    dispatch_id         BIGINT       NOT NULL REFERENCES dispatch_orders (id),
    store_id            BIGINT       NOT NULL REFERENCES stores (id),
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING_REVIEW',
    -- PENDING_REVIEW 待定责 / DETERMINED 已定责 / RETURNING 返运中 / REPROCESSED 二次处理完成 / CLOSED 结案
    evidence_json       TEXT         NOT NULL,        -- 证据快照：交接照片/封桶记录/轨迹/到厂检测
    suggested_liability VARCHAR(24),                  -- 系统建议责任方 MERCHANT_SOURCE/TRANSPORT/UNDETERMINED
    liability_party     VARCHAR(24),                  -- 认定责任方 MERCHANT_SOURCE/TRANSPORT
    determination_note  VARCHAR(1024),                -- 定责说明
    extra_fee_amount    NUMERIC(12, 2),               -- 额外处理费
    fee_bearer          VARCHAR(16),                  -- 费用承担方 MERCHANT/FLEET（由责任方推导，防止推责）
    determined_by       VARCHAR(64),
    determined_at       TIMESTAMPTZ,
    return_route        VARCHAR(512),                 -- 返运路线
    temp_storage_point  VARCHAR(256),                 -- 临时存放点
    return_arranged_at  TIMESTAMPTZ,
    secondary_result    VARCHAR(1024),                -- 二次处理结果
    reprocessed_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_rejection_store ON rejection_cases (store_id);
CREATE INDEX idx_rejection_status ON rejection_cases (status);

-- ============ 结算额外费用单（费用来源留痕，避免互相推责） ============
CREATE TABLE settlement_fees (
    id                BIGSERIAL PRIMARY KEY,
    manifest_id       BIGINT         NOT NULL REFERENCES manifests (id),
    store_id          BIGINT         NOT NULL REFERENCES stores (id),
    rejection_case_id BIGINT         NOT NULL REFERENCES rejection_cases (id),
    fee_type          VARCHAR(32)    NOT NULL,   -- EXTRA_PROCESSING 额外处理费
    amount            NUMERIC(12, 2) NOT NULL,
    bearer            VARCHAR(16)    NOT NULL,   -- MERCHANT 商户 / FLEET 司机与车队
    bearer_driver_id  BIGINT,                     -- 承担方为车队时的责任司机
    bearer_vehicle_id BIGINT,                     -- 承担方为车队时的责任车辆
    source_note       VARCHAR(512)   NOT NULL,   -- 费用来源说明
    status            VARCHAR(16)    NOT NULL DEFAULT 'CONFIRMED',
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_fees_manifest ON settlement_fees (manifest_id);
CREATE INDEX idx_fees_store ON settlement_fees (store_id);
