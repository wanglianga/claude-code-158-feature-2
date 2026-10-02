#!/usr/bin/env bash
# 端到端冒烟验证：预约 → 派单 → 扫码核验 → 称重 → 运输 → 二次称重 → 合规闭环 → 结算
# 以及主要异常场景。前提：应用已在 localhost:8080 运行。
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

# 解析 JSON 字段
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

req() { # method path role account body
  curl -s -X "$1" "$BASE$2" -H "X-Role: $3" -H "X-Account-Id: $4" \
       -H "Content-Type: application/json" ${5:+-d "$5"}
}

check() { # 描述 实际值 期望值
  if [ "$2" == "$3" ]; then PASS=$((PASS+1)); echo "  ✔ $1 ($2)";
  else FAIL=$((FAIL+1)); echo "  ✘ $1 期望[$3] 实际[$2]"; fi
}

tomorrow() { python3 -c "
from datetime import datetime,timedelta,timezone
d=(datetime.now(timezone.utc)+timedelta(days=1)).replace(hour=$1,minute=$2,second=0,microsecond=0)
# 明天北京时间 $1:$2 = UTC $1-8:$2
print(d.isoformat().replace('+00:00','Z'))"; }
# 北京时间明天 10:00 / 11:00（UTC 02:00 / 03:00）
WS=$(tomorrow 2 0); WE=$(tomorrow 3 0)

echo "=== 1. 主流程：预约→派单→核验→称重→运输→二次称重→闭环→结算 ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-001/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":2,\"estimatedWeightKg\":300,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
echo "  预约 ID=$AID"
DID=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\",\"routeNote\":\"春熙路→经开区\"}")")
echo "  派单 ID=$DID"
req POST /api/dispatches/$DID/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID/arrive DRIVER driver-1 '{}' >/dev/null
V=$(req POST /api/dispatches/$DID/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-001","handoverName":"王店长","photoUrls":"https://img.example.com/site1.jpg"}')
check "扫码核验后进入装车" "$(jget "['data']['status']" <<< "$V")" "LOADING"
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID,\"barrelCode\":\"BAR-001\",\"weightKg\":150,\"deviceCode\":\"DEV-001\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w1.jpg\",\"handoverName\":\"王店长\"}" >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID,\"barrelCode\":\"BAR-002\",\"weightKg\":148,\"deviceCode\":\"DEV-001\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w2.jpg\",\"handoverName\":\"王店长\"}" >/dev/null
req POST /api/dispatches/$DID/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID DRIVER driver-1 '{"points":[{"lat":30.66,"lng":104.06},{"lat":30.60,"lng":104.10}]}' >/dev/null
req POST /api/dispatches/$DID/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID: print(m['id']); break")
echo "  联单 ID=$MID"
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID,\"arrivalWeightKg\":296,\"barrelCount\":2,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
DETAIL=$(req GET /api/manifests/$MID REGULATOR regulator-1)
check "联单闭环 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$DETAIL")" "CLOSED"
check "合规结果 PASS" "$(jget "['data']['manifest']['complianceResult']" <<< "$DETAIL")" "PASS"
check "出店重量 298kg" "$(jget "['data']['manifest']['exitWeightKg']" <<< "$DETAIL")" "298.0"
check "到厂重量 296kg" "$(jget "['data']['manifest']['arrivalWeightKg']" <<< "$DETAIL")" "296.0"
check "结算金额 947.2 元" "$(jget "['data']['settlement']['amount']" <<< "$DETAIL")" "947.2"
check "称重记录 2 条" "$(jget "['data']['weighings'].__len__()" <<< "$DETAIL")" "2"
check "轨迹点 2 个" "$(jget "['data']['tracks'].__len__()" <<< "$DETAIL")" "2"

echo "=== 2. 一桶一码追溯 ==="
TRACE=$(req GET /api/barrels/BAR-001/trace REGULATOR regulator-1)
check "BAR-001 已到厂" "$(jget "['data']['barrel']['status']" <<< "$TRACE")" "AT_PLANT"
check "BAR-001 有称重档案" "$(jget "['data']['weighingRecords'].__len__()" <<< "$TRACE")" "1"

echo "=== 3. 商户质疑重量 → 冻结结算 → 协商调整 ==="
DIS=$(req POST /api/disputes MERCHANT merchant-1 "{\"manifestId\":$MID,\"reason\":\"对运输损耗 2kg 有异议\"}")
DISID=$(jget "['data']['id']" <<< "$DIS")
check "质疑后联单 DISPUTED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID MERCHANT merchant-1)")" "DISPUTED"
check "结算冻结 FROZEN" "$(jget "['data']['status']" <<< "$(req GET /api/settlements/by-manifest/$MID MERCHANT merchant-1)")" "FROZEN"
req POST /api/disputes/$DISID/resolve RECYCLER recycler-1 '{"resolution":"双方复核后按 297kg 结算","adjustedWeightKg":297}' >/dev/null
check "解决后联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID MERCHANT merchant-1)")" "CLOSED"
check "调整后金额 950.4 元" "$(jget "['data']['amount']" <<< "$(req GET /api/settlements/by-manifest/$MID MERCHANT merchant-1)")" "950.4"

echo "=== 4. 商户临时取消 ==="
AID2=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID2=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID2,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/appointments/$AID2/cancel MERCHANT merchant-1 '{"reason":"今日店休"}' >/dev/null
check "预约已取消" "$(jget "['data']['status']" <<< "$(req GET /api/appointments/$AID2 MERCHANT merchant-1)")" "CANCELLED"
check "派单联动取消" "$(jget "['data']['status']" <<< "$(req GET /api/dispatches/$DID2 RECYCLER recycler-1)")" "CANCELLED"
check "取消异常工单" "$(req GET "/api/exceptions/by-ref?refType=APPOINTMENT&refId=$AID2" RECYCLER recycler-1 | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['type'])")" "MERCHANT_CANCEL"

echo "=== 5. 手动补录（设备故障） ==="
AID3=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID3=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID3,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID3/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID3/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID3/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-001","handoverName":"王店长"}' >/dev/null
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/weighings" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DID3,\"barrelCode\":\"BAR-003\",\"weightKg\":120,\"source\":\"MANUAL\"}")
check "补录无原因被拒(400)" "$CODE" "400"
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID3,\"barrelCode\":\"BAR-003\",\"weightKg\":120,\"source\":\"MANUAL\",\"note\":\"设备故障人工复核\",\"photoUrl\":\"https://img.example.com/m1.jpg\"}" >/dev/null
MID3=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID3: print(m['id']); break")
check "联单记录补录 1 条" "$(jget "['data']['manifest']['manualWeighCount']" <<< "$(req GET /api/manifests/$MID3 REGULATOR regulator-1)")" "1"

echo "=== 6. 处置厂拒收（混入厨余）→ 责任判断 → 额外费用 → 返运二次处理 ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-002/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID4=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":2,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID4=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID4,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID4/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4/verify DRIVER driver-1 '{"storeQr":"STORE-QR-2","deviceCode":"DEV-002","handoverName":"陈店长","photoUrls":"https://img.example.com/handover101.jpg"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID4,\"barrelCode\":\"BAR-101\",\"weightKg\":100,\"deviceCode\":\"DEV-002\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w101.jpg\"}" >/dev/null
# 司机封桶：留封条编号与加封照片
SEAL=$(req POST /api/dispatches/$DID4/seals DRIVER driver-1 '{"barrelCode":"BAR-101","sealNo":"SEAL-101-A","photoUrl":"https://img.example.com/seal101.jpg"}')
check "封桶记录已登记" "$(jget "['data']['sealNo']" <<< "$SEAL")" "SEAL-101-A"
req POST /api/dispatches/$DID4/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID4 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID4/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID4=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID4: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID4,\"arrivalWeightKg\":100,\"barrelCount\":1,\"qualityCheck\":\"CONTAMINATED\",\"sealCheck\":\"INTACT\",\"decision\":\"REJECT\",\"rejectReason\":\"混入大量厨余残渣\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "联单 REJECTED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID4 REGULATOR regulator-1)")" "REJECTED"

echo "=== 6a. 拒收责任判断：证据追溯 → 商户源头混入 → 商户承担额外处理费 ==="
CASE=$(req GET /api/rejections/by-manifest/$MID4 REGULATOR regulator-1)
CASEID=$(jget "['data']['rejectionCase']['id']" <<< "$CASE")
check "拒收案件自动建立(待定责)" "$(jget "['data']['rejectionCase']['status']" <<< "$CASE")" "PENDING_REVIEW"
check "系统建议商户源头混入" "$(jget "['data']['rejectionCase']['suggestedLiability']" <<< "$CASE")" "MERCHANT_SOURCE"
check "证据含封桶记录1条" "$(jget "['data']['evidence']['sealedBarrelCount']" <<< "$CASE")" "1"
check "证据含交接照片" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['evidence']['handoverPhotos'])>=1)" <<< "$CASE")" "True"
check "证据含到厂封条核验" "$(jget "['data']['evidence']['arrivalSealCheck']" <<< "$CASE")" "INTACT"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/rejections/$CASEID/determine" -H "X-Role: RECYCLER" -H "Content-Type: application/json" -d '{"liabilityParty":"MERCHANT_SOURCE","extraFeeAmount":200}')
check "定责无说明被拒(400)" "$CODE" "400"
DET=$(req POST /api/rejections/$CASEID/determine RECYCLER recycler-1 '{"liabilityParty":"MERCHANT_SOURCE","note":"封条完好且交接照片可见残渣，判定商户源头混入","extraFeeAmount":200}')
check "定责后案件 DETERMINED" "$(jget "['data']['status']" <<< "$DET")" "DETERMINED"
check "费用承担方为商户" "$(jget "['data']['feeBearer']" <<< "$DET")" "MERCHANT"
FEE=$(req GET "/api/settlements/fees?manifestId=$MID4" MERCHANT merchant-1)
check "费用单金额 200 元" "$(jget "['data'][0]['amount']" <<< "$FEE")" "200.0"
check "费用来源写明商户源头混入" "$(python3 -c "import sys,json;print('商户源头混入' in json.load(sys.stdin)['data'][0]['sourceNote'])" <<< "$FEE")" "True"
check "联单详情含拒收案件" "$(jget "['data']['rejectionCase']['id']" <<< "$(req GET /api/manifests/$MID4 REGULATOR regulator-1)")" "$CASEID"

echo "=== 6b. 返运 → 临时存放 → 二次处理 → 结案 ==="
RET=$(req POST /api/rejections/$CASEID/return RECYCLER recycler-1 '{"returnRoute":"处置厂→经开区暂存点→二次处理车间","tempStoragePoint":"经开区环保园暂存仓A-3"}')
check "返运登记(路线+存放点)后 RETURNING" "$(jget "['data']['status']" <<< "$RET")" "RETURNING"
REP=$(req POST /api/rejections/$CASEID/reprocess RECYCLER recycler-1 '{"secondaryResult":"厨余残渣分离后二次处置完成，产出工业原料油82kg"}')
check "二次处理结果登记后 REPROCESSED" "$(jget "['data']['status']" <<< "$REP")" "REPROCESSED"
check "结案 CLOSED" "$(jget "['data']['status']" <<< "$(req POST /api/rejections/$CASEID/close RECYCLER recycler-1 '{}')")" "CLOSED"
TICKET=$(req GET "/api/exceptions/by-ref?refType=MANIFEST&refId=$MID4" RECYCLER recycler-1 | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['type']=='PLANT_REJECT': print(t['status']); break")
check "结案后拒收工单联动处理" "$TICKET" "RESOLVED"

echo "=== 6c. 运输中污染：未留封桶记录 → 司机与车队担责 ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-003/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID4B=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-2 "{\"storeId\":3,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID4B=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID4B,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID4B/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4B/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4B/verify DRIVER driver-1 '{"storeQr":"STORE-QR-3","deviceCode":"DEV-003","handoverName":"刘老板"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID4B,\"barrelCode\":\"BAR-201\",\"weightKg\":100,\"deviceCode\":\"DEV-003\",\"source\":\"DEVICE\"}" >/dev/null
req POST /api/dispatches/$DID4B/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID4B DRIVER driver-1 '{"points":[{"lat":30.61,"lng":104.03}]}' >/dev/null
req POST /api/dispatches/$DID4B/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID4B=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID4B: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID4B,\"arrivalWeightKg\":100,\"barrelCount\":1,\"qualityCheck\":\"CONTAMINATED\",\"decision\":\"REJECT\",\"rejectReason\":\"混入厨余，运输环节无法自证\",\"receivedBy\":\"厂验收员\"}" >/dev/null
CASEB=$(req GET /api/rejections/by-manifest/$MID4B REGULATOR regulator-1)
CASEIDB=$(jget "['data']['rejectionCase']['id']" <<< "$CASEB")
check "未封桶建议运输中污染" "$(jget "['data']['rejectionCase']['suggestedLiability']" <<< "$CASEB")" "TRANSPORT"
DETB=$(req POST /api/rejections/$CASEIDB/determine RECYCLER recycler-1 '{"liabilityParty":"TRANSPORT","note":"未留封桶记录，运输环节无法自证清白","extraFeeAmount":300}')
check "费用承担方为司机与车队" "$(jget "['data']['feeBearer']" <<< "$DETB")" "FLEET"
FEEB=$(req GET "/api/settlements/fees?manifestId=$MID4B" RECYCLER recycler-1)
check "费用单记录责任司机" "$(jget "['data'][0]['bearerDriverId']" <<< "$FEEB")" "1"
check "无需返运直接结案" "$(jget "['data']['status']" <<< "$(req POST /api/rejections/$CASEIDB/close RECYCLER recycler-1 '{}')")" "CLOSED"

echo "=== 7. 运输损耗异常 → 阻断闭环 → 处理后恢复 ==="
AID5=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":2,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID5=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID5,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID5/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID5/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID5/verify DRIVER driver-1 '{"storeQr":"STORE-QR-2","deviceCode":"DEV-002","handoverName":"陈店长"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID5,\"barrelCode\":\"BAR-102\",\"weightKg\":100,\"deviceCode\":\"DEV-002\",\"source\":\"DEVICE\"}" >/dev/null
req POST /api/dispatches/$DID5/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID5 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID5/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID5=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID5: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID5,\"arrivalWeightKg\":80,\"barrelCount\":1,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "损耗 20% 联单 EXCEPTION" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID5 REGULATOR regulator-1)")" "EXCEPTION"
TID=$(req GET "/api/exceptions/by-ref?refType=MANIFEST&refId=$MID5" RECYCLER recycler-1 | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['type']=='TRANSPORT_LOSS_ABNORMAL': print(t['id']); break")
req POST /api/exceptions/$TID/resolve RECYCLER recycler-1 '{"resolution":"确认为阀门渗漏，已维修备案"}' >/dev/null
check "工单处理后备联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID5 REGULATOR regulator-1)")" "CLOSED"

echo "=== 8. 监管抽查 + 风险名单 + 报表 ==="
req POST /api/inspections REGULATOR regulator-1 "{\"manifestId\":$MID,\"result\":\"PASS\",\"note\":\"抽查合格\"}" >/dev/null
req POST /api/risks/scan REGULATOR regulator-1 '{}' >/dev/null
RISKS=$(req GET /api/risks REGULATOR regulator-1)
check "风险名单非空" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$RISKS")" "True"
EFF=$(req GET /api/reports/vehicle-efficiency RECYCLER recycler-1)
check "车辆效率报表" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$EFF")" "True"
CERT=$(req GET /api/reports/stores/1/certificate MERCHANT merchant-1)
check "商户回收证明联单数≥1" "$(python3 -c "import sys,json;print(json.load(sys.stdin)['data']['manifestCount']>=1)" <<< "$CERT")" "True"
TREND=$(req GET /api/reports/regulator/trend REGULATOR regulator-1)
check "监管趋势报表" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$TREND")" "True"

echo "=== 9. Kafka 事件审计流水 ==="
AUDIT=$(psql "postgresql://wasteoil:wasteoil123@localhost:5433/wasteoil" -tAc "SELECT count(*) FROM audit_logs" 2>/dev/null || echo "0")
check "审计流水已落库(>20 条)" "$(python3 -c "print($AUDIT>20)")" "True"

echo ""
echo "=========================================="
echo "冒烟结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" == "0" ] && echo "SMOKE_OK" || exit 1
