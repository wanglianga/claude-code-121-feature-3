# 药店冷藏药品配送签收与温控赔付服务

基于 **Kotlin + Spring Boot 3.3 + Redis** 的冷藏药品（胰岛素、生物制剂、眼药水、特殊冷藏药品）配送签收与温控赔付服务。

覆盖能力：

- **下单与审核**：处方照片归档 → 处方审核 → 药师复核 →（拣货）→ 出库（批号、有效期、保温箱编号、冰袋数量、出库温度、预计送达时间）
- **拣货与预结算**：仓库拣货即按批号锁定库存并完成医保预结算；之后才能出库
- **处方退回联动**：药师审核发现处方剂量或适应症不符 → 暂停出库并通知患者补充材料；**药品已拣货但未出库时，库存锁定、医保结算、患者通知同步回滚**；患者补充后药师重新审核，库存重新锁定（锁定时间更新）、医保重新预结算，患者端看到恢复出库条件；**恢复出库前药师需再次确认**；全程药师留痕
- **配送监控**：骑手取药后持续上报箱内温度、路线、开箱次数、配送状态；超温 / 开盖异常自动产生异常事件
- **患者签收**：本人/代收人身份确认、药品外观、温度标签、用药提醒确认
- **异常协同**：处方退回、骑手绕路超温、患者不在家、开盖异常、包装破损、患者退药、药师判不可用——异常事件把药师、仓库、骑手、患者、客服、医保结算、赔付人员连接到同一订单
- **最终处理**：药品报损 / 退回库存、补发（生成 0 元新订单）、退款（医保/自费自动拆分）、骑手责任与扣款、患者用药风险提醒
- **配套能力**：处方照片归档、冷链箱循环使用、骑手培训（未培训不能取药）、医保自费拆分、患者用药回访、患者通知（下发/撤回）
- **核心规则**：冷藏药品一旦出库，任何签收失败、超温或退药都会把药品可用性置为 `PENDING_REJUDGMENT`，必须药师重新判断后才能继续配送或结案
- **双视角**：药店端按订单查看药品是否仍可使用；患者端查看超温、补发、退款、用药提醒与处方退回/恢复出库的当前状态
- **接口文档**：内置 OpenAPI 3.x 文档与 Swagger UI（见下文）

## 本地启动

### 方式一：Docker Compose 一键启动（推荐，自带 Redis）

前置条件：Docker（含 Compose 插件）。

```bash
docker compose up --build
# 服务监听 http://localhost:8080 ，Redis 监听 localhost:6379
```

停止：`docker compose down`（加 `-v` 同时清空 Redis 数据卷）。

### 方式二：本地直接运行

前置条件：JDK 17+、Maven 3.6+、Redis（默认 `localhost:6379`，可用环境变量覆盖）。

```bash
# 1. 启动 Redis（已运行可跳过）
redis-server --daemonize yes

# 2. 启动服务（默认 8080 端口）
mvn spring-boot:run

# 或者打包后运行
mvn package -DskipTests
java -jar target/cold-chain-delivery-1.0.0.jar
```

环境变量：`REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` / `SERVER_PORT`。

### 接口文档（OpenAPI 3.x / Swagger UI）

服务启动后访问：

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 测试账号（请求头鉴权）

所有接口通过请求头声明身份，未携带返回 401，角色不符返回 403：

| 请求头 | 说明 |
|---|---|
| `X-User-Id` | 用户标识，如 `pharmacist-1` |
| `X-Role` | 角色，见下表 |

| 角色 | 测试账号示例 | 职责 |
|---|---|---|
| `PHARMACIST` | `pharmacist-1` | 处方审核、药师复核、**处方退回发起/重审/恢复确认**、药师重新判断 |
| `WAREHOUSE` | `warehouse-1` | 入库、**拣货**、出库、保温箱回收 |
| `RIDER` | `rider-1` | 取药、遥测上报、配送尝试 |
| `PATIENT` | `patient-1` | 签收、退药、**补充处方材料**、查看本人订单与通知 |
| `CUSTOMER_SERVICE` | `cs-1` | 异常处理、用药回访 |
| `INSURANCE` | `insurance-1` | 医保自费拆分、退款、查看医保结算 |
| `COMPENSATION` | `comp-1` | 赔付处理（报损/补发/退款/骑手责任） |
| `ADMIN` | `admin-1` | 骑手培训录入、入库，放行所有接口 |

## 订单状态机

```
PRESCRIPTION_REVIEWING → PRESCRIPTION_REJECTED（审核退回，可补照片重审）
                     → PHARMACIST_REVIEWING → READY_FOR_OUTBOUND
READY_FOR_OUTBOUND ──拣货(锁库存+医保预结算)──> PICKED
READY_FOR_OUTBOUND / PICKED
   └─药师发起处方退回(剂量/适应症不符)─> OUTBOUND_PAUSED
        （已拣货：库存锁定释放、医保预结算回滚、拣货通知撤回）
        └─患者补充材料─> RESUPPLIED(case)
             └─药师重新审核通过─> RESUME_CONFIRMATION_PENDING（库存重锁、医保重新预结算）
                  └─药师再次确认恢复─> PICKED（已拣货）/ READY_FOR_OUTBOUND（未拣货）
READY_FOR_OUTBOUND / PICKED → OUTBOUND → DELIVERING → SIGNED
DELIVERING --超温/开盖异常/包装破损--> DELIVERY_EXCEPTION --药师判可用--> DELIVERING
DELIVERING --患者不在家--> SIGN_FAILED --药师判可用--> DELIVERING
SIGNED/DELIVERING --患者退药--> RETURN_REQUESTED
（以上异常均使 usability = PENDING_REJUDGMENT，必须药师重新判断）
药师判不可用 --> AWAITING_RESOLUTION --赔付处理--> CLOSED / REISSUED（生成补发新订单）
```

处方退回联动 case 自身状态：`OPEN`（等待补充）→ `RESUPPLIED`（已补充待重审）→ `RE_REVIEWED`（重审通过待最终确认）→ `RESUMED`（已恢复出库）。

## 主要接口

### 药店/药房侧
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/orders` | PHARMACIST, CUSTOMER_SERVICE | 创建订单（含医保金额，自动算自费） |
| GET | `/api/orders?status=&patientId=` | 药房各角色 | 订单列表 |
| GET | `/api/orders/{id}` | 药房各角色 | 药店视角详情（含可用性、库存锁定、医保结算、退回 case、遥测、事件、赔付） |
| GET | `/api/orders/{id}/usability` | 药房各角色 | **药品是否仍可使用** |
| POST | `/api/orders/{id}/prescription-photos` | PHARMACIST, CUSTOMER_SERVICE | 处方照片归档（审核前必须至少一张） |
| POST | `/api/orders/{id}/prescription-review` | PHARMACIST | 处方审核（退回产生异常事件） |
| POST | `/api/orders/{id}/pharmacist-review` | PHARMACIST | 药师复核 |
| POST | `/api/stock/restock` | WAREHOUSE, ADMIN | 入库（按药品+批号增加可用库存） |
| GET | `/api/stock` | WAREHOUSE, PHARMACIST, COMPENSATION | 库存账本 |
| GET | `/api/orders/{id}/stock-locks` | WAREHOUSE, PHARMACIST, COMPENSATION, INSURANCE | 订单库存锁定记录（含释放/重锁留痕） |
| POST | `/api/orders/{id}/pick` | WAREHOUSE | **拣货：锁定库存 + 医保预结算，进入 PICKED** |
| POST | `/api/orders/{id}/prescription-return` | PHARMACIST | **发起处方退回：暂停出库；已拣货未出库则库存/医保/通知三项同步回滚，通知患者补材料** |
| POST | `/api/orders/{id}/re-review` | PHARMACIST | **对补充材料重新审核：通过则库存重锁（锁定时间更新）、医保重新预结算** |
| POST | `/api/orders/{id}/resume-confirmation` | PHARMACIST | **恢复出库前再次确认（可注明恢复条件）** |
| GET | `/api/orders/{id}/prescription-return` | 药房各角色 | 退回联动 case 列表与当前进行中 case（含药师审计轨迹） |
| POST | `/api/orders/{id}/outbound` | WAREHOUSE | 出库（批号须与拣货批号一致；锁定转扣减、医保正式结算；出库温度须在 2-8°C） |
| POST | `/api/orders/{id}/rejudgment` | PHARMACIST | **药师重新判断药品是否可用** |
| GET | `/api/orders/{id}/resolution/suggestion` | COMPENSATION 等 | 赔付建议（按异常自动推导） |
| POST | `/api/orders/{id}/resolution` | COMPENSATION | 赔付处理：报损/补发/退款/骑手责任/风险提醒 |
| POST | `/api/orders/{id}/follow-ups` | CUSTOMER_SERVICE, PHARMACIST | 用药回访（签收后） |

> 暂停出库期间（`OUTBOUND_PAUSED` / `RESUME_CONFIRMATION_PENDING`），出库、骑手取药等后续操作一律返回 409，直到药师再次确认恢复。

### 骑手侧
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/pickup` | 取药（须已通过冷链培训） |
| POST | `/api/orders/{id}/telemetry` | 上报温度/路线/开箱/配送状态；超温或开盖次数 >1 自动产生异常事件 |
| POST | `/api/orders/{id}/delivery-attempt` | 配送尝试（`PATIENT_NOT_HOME` → 签收失败待药师重判） |
| GET | `/api/orders/{id}/telemetry` | 遥测记录 |
| POST | `/api/riders/{riderId}/trainings` | 录入培训（ADMIN/CUSTOMER_SERVICE） |

### 患者侧
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/sign` | 签收（身份/外观/温度标签/用药提醒确认；外观或标签异常 → 签收失败并上报） |
| POST | `/api/orders/{id}/return-request` | 申请退药 |
| POST | `/api/orders/{id}/supplement` | **处方退回后补充处方材料（只能本人）** |
| GET | `/api/patient/orders/{id}` | **患者视角：超温、补发、退款、用药提醒、处方退回/恢复出库状态** |
| GET | `/api/patient/notifications` | **患者通知列表（退回补充 / 重审通过 / 恢复出库；撤回的拣货通知标 RECALLED）** |

### 异常事件 / 医保 / 保温箱
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/incidents` | 任意角色上报异常 |
| GET | `/api/orders/{id}/incidents` | 订单异常事件列表 |
| POST | `/api/incidents/{id}/resolve` | 客服/赔付关闭事件 |
| GET | `/api/orders/{id}/payment-split` | 医保/自费拆分、退款进度与结算状态（NONE/PRE_SETTLED/ROLLED_BACK/SETTLED） |
| POST | `/api/orders/{id}/refund` | 退款（按医保占比自动拆分退回渠道） |
| GET | `/api/boxes` | 保温箱列表 |
| POST | `/api/boxes/{boxNo}/recycle` | 回收：完好 → READY 循环再用；破损 → RETIRED |

## 处方退回联动规则要点

1. 药师在 `READY_FOR_OUTBOUND` 或 `PICKED` 阶段可凭 `DOSAGE_MISMATCH`（剂量不符）/`INDICATION_MISMATCH`（适应症不符）发起退回，订单进入 `OUTBOUND_PAUSED`，出库被服务侧暂停。
2. 退回时若**已拣货未出库**：库存锁定释放并把数量加回可用库存、医保预结算回滚（额度解冻）、"已拣货即将出库"患者通知撤回，三项在同一次操作内同步完成；若尚未拣货，仅暂停出库。
3. 患者补充材料后，药师重新审核：不通过则继续暂停等待补充；通过则库存按原批号**重新锁定（生成新锁定记录，锁定时间更新，lockNo 递增）**、医保**重新预结算**，订单进入 `RESUME_CONFIRMATION_PENDING`，患者端可看到"恢复出库条件"。
4. **恢复出库前必须由药师再次确认**（`/resume-confirmation`），已拣货订单回到 `PICKED`、未拣货回到 `READY_FOR_OUTBOUND`，随后可正常出库（锁定转扣减、医保正式结算）。
5. 全链路在处方退回 case（`returncase`）与异常事件中留痕，case 的 `auditTrail` 记录发起、回滚、补充、重锁、重审、恢复确认每一步的操作药师与时间。

## 验证方式

### 1. 自动化测试（实际执行过）

```bash
redis-server --daemonize yes   # 测试使用 Redis database 1，不影响默认库
mvn test
```

`ColdChainFlowTests` 共 **16** 个端到端用例，覆盖：正常签收全流程、处方审核退回重审、绕路超温赔付（报损+补发+全额退款+骑手全责+风险提醒）、患者不在家恢复配送、开盖异常、包装破损退库、签收后退药、温度标签异常、未培训骑手拦截、角色/归属权限、医保拆分与部分退款、出库温度与保温箱占用校验，以及处方退回联动：

- **已拣货未出库退回：库存/医保/通知三项回滚 → 患者补充 → 药师重审（库存重锁、锁定时间更新、医保重新预结算）→ 药师再次确认 → 正常出库扣减与正式结算**，并校验暂停期间出库/取药被拦截、患者端恢复条件、药师审计轨迹；
- 未拣货退回的轻量路径（仅暂停，无三项回滚，恢复后回到待出库再拣货）；
- 重审不通过保持暂停、继续补充后通过；
- 边界：非药师不能发起、他人不能补充、库存不足时重锁失败、无进行中 case 不能确认恢复。

### 2. 手工冒烟（curl 全流程）

启动服务后执行（下面脚本可直接复制运行）：

```bash
# 1) 创建订单（总额 1000 元，医保 700，自费 300）
curl -s -X POST localhost:8080/api/orders \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"patientId":"patient-1","patientName":"张三","drugType":"INSULIN","drugName":"门冬胰岛素注射液","quantity":2,"prescriptionNo":"RX-2026-0001","totalAmountCents":100000,"insuranceAmountCents":70000}'
# → 记录返回的 id，以下用 $OID 代替

# 2) 处方照片归档 → 处方审核 → 药师复核
curl -s -X POST localhost:8080/api/orders/$OID/prescription-photos -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"url":"https://img.example.com/rx1.jpg"}'
curl -s -X POST localhost:8080/api/orders/$OID/prescription-review -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"approved":true}'
curl -s -X POST localhost:8080/api/orders/$OID/pharmacist-review -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"approved":true}'

# 3) 拣货（锁定库存 + 医保预结算）：先入库再拣货
curl -s -X POST localhost:8080/api/stock/restock -H "X-Role: WAREHOUSE" -H "X-User-Id: warehouse-1" -H "Content-Type: application/json" \
  -d '{"drugName":"门冬胰岛素注射液","batchNo":"B20260901","qty":5}'
curl -s -X POST localhost:8080/api/orders/$OID/pick -H "X-Role: WAREHOUSE" -H "X-User-Id: warehouse-1" -H "Content-Type: application/json" \
  -d '{"batchNo":"B20260901"}'
# → status=PICKED；/payment-split 中 settlementStatus=PRE_SETTLED；/api/stock 可用量 5→3

# 4) 药师发现剂量不符，发起处方退回（暂停出库 + 三项回滚）
curl -s -X POST localhost:8080/api/orders/$OID/prescription-return -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"reason":"DOSAGE_MISMATCH","detail":"单次剂量超说明书上限"}'
# → status=OUTBOUND_PAUSED；库存回 5；settlementStatus=ROLLED_BACK；拣货通知 RECALLED

# 5) 患者补充材料 → 药师重新审核 → 药师再次确认恢复
curl -s -X POST localhost:8080/api/orders/$OID/supplement -H "X-Role: PATIENT" -H "X-User-Id: patient-1" -H "Content-Type: application/json" \
  -d '{"url":"https://img.example.com/diag.jpg","note":"补充诊断证明"}'
curl -s -X POST localhost:8080/api/orders/$OID/re-review -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"approved":true,"comment":"剂量已更正为 6 单位，符合说明书"}'
# → 库存重锁（可用量 3）、settlementStatus=PRE_SETTLED；订单 RESUME_CONFIRMATION_PENDING
curl -s -X POST localhost:8080/api/orders/$OID/resume-confirmation -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"condition":"按更正后剂量发药并电话告知患者"}'
# → case RESUMED；订单回到 PICKED

# 6) 恢复后正常出库
curl -s -X POST localhost:8080/api/orders/$OID/outbound -H "X-Role: WAREHOUSE" -H "X-User-Id: warehouse-1" -H "Content-Type: application/json" \
  -d '{"batchNo":"B20260901","expiryDate":"2027-06-30","boxNo":"BOX-001","icePackCount":2,"outboundTempC":5.0,"etaMinutes":45}'
# → settlementStatus=SETTLED；锁定转 DEDUCTED

# 7) 患者端查看退回/恢复状态与通知
curl -s localhost:8080/api/patient/orders/$OID -H "X-Role: PATIENT" -H "X-User-Id: patient-1"
curl -s localhost:8080/api/patient/notifications -H "X-Role: PATIENT" -H "X-User-Id: patient-1"
```

超温赔付流程（配送途中上报 `{"tempC":12.5,"detour":true}`）：

```bash
curl -s -X POST localhost:8080/api/orders/$OID/telemetry -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" -d '{"tempC":12.5,"detour":true}'
# → 订单进入 DELIVERY_EXCEPTION，自动生成 RIDER_DETOUR_OVERTEMP 事件，usability=PENDING_REJUDGMENT
curl -s -X POST localhost:8080/api/orders/$OID/rejudgment -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"usable":false,"comment":"长时间超温，效价无法保证"}'
curl -s localhost:8080/api/orders/$OID/resolution/suggestion -H "X-Role: COMPENSATION" -H "X-User-Id: comp-1"
curl -s -X POST localhost:8080/api/orders/$OID/resolution -H "X-Role: COMPENSATION" -H "X-User-Id: comp-1" -H "Content-Type: application/json" \
  -d '{"drugDisposition":"SCRAPPED","reissue":true,"refundAmountCents":100000,"riderLiability":"FULL","riderFineCents":50000,"riskReminder":"本单药品曾因超温报损，请勿使用原配送药品"}'
# → 原订单 REISSUED，补发新订单 READY_FOR_OUTBOUND（0 元），退款按 7:3 拆回医保/自费
```

## 数据存储（Redis key）

| Key | 内容 |
|---|---|
| `order:{id}` / `idx:status:{s}` / `idx:patient:{pid}` | 订单 JSON 与索引 |
| `telemetry:{orderId}` | 温度/路线遥测 list |
| `incident:{id}` / `idx:incident:order:{orderId}` | 异常事件 |
| `rejudgment:{orderId}` | 药师重判记录 |
| `resolution:{orderId}` | 最终赔付结果 |
| `payment:{orderId}` / `settlement:{orderId}` | 医保/自费拆分（含结算状态）与结算事件流水 |
| `stock:{drugName}:{batchNo}` / `idx:stock` | 库存商品可用量与索引 |
| `lock:{lockId}` / `idx:lock:order:{orderId}` | 库存锁定（拣货锁定/退回释放/重锁/出库扣减） |
| `returncase:{caseId}` / `idx:returncase:order:{orderId}` | 处方退回联动 case（含审计轨迹） |
| `notif:{id}` / `idx:notif:order:{orderId}` / `idx:notif:patient:{pid}` | 患者通知与索引 |
| `box:{boxNo}` | 保温箱循环记录 |
| `training:{riderId}` / `followup:{orderId}` / `rxphoto:{orderId}` | 培训 / 回访 / 处方照片 |

## 项目结构

```
src/main/kotlin/com/pharmacy/coldchain/
├── ColdChainApplication.kt
├── common/          # 鉴权(Auth)、异常、全局错误处理、OpenAPI 配置
├── domain/          # 枚举、模型、请求/视图 DTO
├── repo/            # JsonStore(Redis JSON 存储)、OrderRepository
├── service/         # 订单/拣货出库/处方退回联动/库存/医保结算/患者通知/配送/事件/赔付/保温箱/骑手/回访
└── controller/      # 订单、库存、配送、患者、事件、保温箱、骑手、支付
src/test/kotlin/...  # ColdChainFlowTests（16 个端到端用例）
Dockerfile / docker-compose.yml   # 一键启动（app + redis）
```
