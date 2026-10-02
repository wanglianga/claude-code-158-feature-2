#!/usr/bin/env bash
# 重置业务数据（保留种子基础数据），用于重复执行冒烟脚本或手工验证。
# 用法: bash scripts/reset-db.sh [数据库名]   默认 wasteoil
set -euo pipefail

DB=${1:-wasteoil}
PGURL="postgresql://wasteoil:wasteoil123@localhost:5433/$DB"

psql "$PGURL" <<'SQL'
TRUNCATE settlement_fees, rejection_cases, barrel_seals, settlements,
         inspections, disputes, exception_tickets, plant_receipts,
         track_points, pickup_verifications, weighing_records,
         manifests, dispatch_orders, appointments, risk_entries, audit_logs
RESTART IDENTITY CASCADE;
UPDATE barrels SET status = 'AT_STORE';
UPDATE weighing_devices SET status = 'ONLINE', last_heartbeat_at = now();
SQL
echo "数据库 $DB 已重置（业务数据清空，油桶/设备恢复种子状态）"
