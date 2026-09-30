package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.domain.BoxStatus
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.CreateOrderRequest
import com.pharmacy.coldchain.domain.DrugType
import com.pharmacy.coldchain.domain.FollowUp
import com.pharmacy.coldchain.domain.IncidentType
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.OutboundRequest
import com.pharmacy.coldchain.domain.OverTempInfo
import com.pharmacy.coldchain.domain.PatientOrderView
import com.pharmacy.coldchain.domain.PharmacyOrderView
import com.pharmacy.coldchain.domain.PhotoRequest
import com.pharmacy.coldchain.domain.PickRequest
import com.pharmacy.coldchain.domain.PrescriptionPhoto
import com.pharmacy.coldchain.domain.NotificationType
import com.pharmacy.coldchain.domain.ReceiverType
import com.pharmacy.coldchain.domain.RefundInfo
import com.pharmacy.coldchain.domain.RefundStatus
import com.pharmacy.coldchain.domain.ReissueInfo
import com.pharmacy.coldchain.domain.Rejudgment
import com.pharmacy.coldchain.domain.RejudgmentRequest
import com.pharmacy.coldchain.domain.Resolution
import com.pharmacy.coldchain.domain.ReturnRequestBody
import com.pharmacy.coldchain.domain.ReviewRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.SignOff
import com.pharmacy.coldchain.domain.SignRequest
import com.pharmacy.coldchain.domain.TelemetryReading
import com.pharmacy.coldchain.domain.TelemetrySummary
import com.pharmacy.coldchain.domain.Usability
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import com.pharmacy.coldchain.repo.OrderRepository
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class OrderService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val incidents: IncidentService,
    private val boxes: BoxService,
    private val payments: PaymentService,
    private val stocks: StockService,
    private val returns: PrescriptionReturnService,
    private val notifications: NotificationService
) {

    // ---------- 下单 ----------

    fun create(req: CreateOrderRequest, creator: String): ColdChainOrder {
        if (req.quantity < 1) throw BadRequestException("药品数量必须 ≥ 1")
        if (req.totalAmountCents < 0) throw BadRequestException("订单金额不能为负")
        if (req.insuranceAmountCents < 0 || req.insuranceAmountCents > req.totalAmountCents) {
            throw BadRequestException("医保金额必须在 0 与订单总额之间")
        }
        val now = System.currentTimeMillis()
        val order = ColdChainOrder(
            id = store.nextId("O"),
            patientId = req.patientId,
            patientName = req.patientName,
            drugType = req.drugType,
            drugName = req.drugName,
            quantity = req.quantity,
            prescriptionNo = req.prescriptionNo,
            totalAmountCents = req.totalAmountCents,
            insuranceAmountCents = req.insuranceAmountCents,
            selfPayAmountCents = req.totalAmountCents - req.insuranceAmountCents,
            status = OrderStatus.PRESCRIPTION_REVIEWING,
            medicationReminders = medicationRemindersFor(req.drugType),
            createdAt = now,
            updatedAt = now
        )
        orders.save(order)
        payments.createFor(order)
        return order
    }

    fun list(status: OrderStatus?, patientId: String?): List<ColdChainOrder> = when {
        status != null -> orders.findByStatus(status)
        patientId != null -> orders.findByPatient(patientId)
        else -> orders.findAll()
    }

    // ---------- 处方照片归档 ----------

    fun addPhoto(orderId: String, req: PhotoRequest, uploader: String): PrescriptionPhoto {
        orders.require(orderId)
        if (req.url.isBlank()) throw BadRequestException("照片地址不能为空")
        val photo = PrescriptionPhoto(
            id = store.nextId("P"),
            orderId = orderId,
            url = req.url,
            uploadedBy = uploader,
            note = req.note,
            at = System.currentTimeMillis()
        )
        store.rpushJson("rxphoto:$orderId", photo)
        return photo
    }

    fun photos(orderId: String): List<PrescriptionPhoto> = store.lrangeJson("rxphoto:$orderId")

    // ---------- 处方审核 / 药师复核 ----------

    fun prescriptionReview(orderId: String, req: ReviewRequest, pharmacist: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.PRESCRIPTION_REVIEWING, OrderStatus.PRESCRIPTION_REJECTED)
        if (photos(orderId).isEmpty()) {
            throw ConflictException("请先归档处方照片，再进行处方审核")
        }
        o.prescriptionReviewer = pharmacist
        o.prescriptionReviewComment = req.comment
        o.prescriptionReviewedAt = System.currentTimeMillis()
        if (req.approved) {
            o.status = OrderStatus.PHARMACIST_REVIEWING
        } else {
            o.status = OrderStatus.PRESCRIPTION_REJECTED
            incidents.report(
                orderId, IncidentType.PRESCRIPTION_REJECTED, Role.PHARMACIST, pharmacist,
                "处方审核退回: ${req.comment ?: "未说明原因"}", dedupOpen = false
            )
        }
        return save(o)
    }

    fun pharmacistReview(orderId: String, req: ReviewRequest, pharmacist: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.PHARMACIST_REVIEWING)
        o.pharmacistReviewer = pharmacist
        o.pharmacistReviewComment = req.comment
        o.pharmacistReviewedAt = System.currentTimeMillis()
        if (req.approved) {
            o.status = OrderStatus.READY_FOR_OUTBOUND
        } else {
            // 复核不通过 → 退回处方环节
            o.status = OrderStatus.PRESCRIPTION_REJECTED
            incidents.report(
                orderId, IncidentType.PRESCRIPTION_REJECTED, Role.PHARMACIST, pharmacist,
                "药师复核不通过，退回处方: ${req.comment ?: "未说明原因"}", dedupOpen = false
            )
        }
        return save(o)
    }

    // ---------- 拣货（库存锁定 + 医保预结算） / 出库 ----------

    /**
     * 仓库拣货：按批号锁定库存并完成医保预结算，订单进入"已拣货待出库"。
     * 此阶段若药师发起处方退回，库存锁定、医保结算、患者通知会同步回滚。
     */
    fun pick(orderId: String, req: PickRequest, operator: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.READY_FOR_OUTBOUND)
        if (o.picked) throw ConflictException("订单 ${o.id} 已拣货，不能重复拣货")
        val lock = stocks.lock(o, req.batchNo, operator)
        payments.preSettle(orderId, operator)

        o.picked = true
        o.pickedAt = System.currentTimeMillis()
        o.pickOperator = operator
        o.stockLockId = lock.id
        o.status = OrderStatus.PICKED
        save(o)
        notifications.send(
            o.id, o.patientId, NotificationType.PICKED_READY,
            "药品已拣货，即将出库",
            "您的 ${o.drugName}（批号 ${req.batchNo}）已拣货完成，医保已预结算，即将冷链出库。",
            operator
        )
        return o
    }

    fun outbound(orderId: String, req: OutboundRequest, operator: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.READY_FOR_OUTBOUND, OrderStatus.PICKED)
        if (req.icePackCount < 1) throw BadRequestException("冰袋数量必须 ≥ 1")
        val expiry = runCatching { LocalDate.parse(req.expiryDate) }.getOrNull()
            ?: throw BadRequestException("有效期格式应为 YYYY-MM-DD: ${req.expiryDate}")
        if (!expiry.isAfter(LocalDate.now())) throw BadRequestException("药品有效期 $expiry 已过期或即将过期，不能出库")
        val range = o.drugType
        if (req.outboundTempC < range.minTempC || req.outboundTempC > range.maxTempC) {
            throw BadRequestException("出库温度 ${req.outboundTempC}°C 超出冷链范围 ${range.minTempC}-${range.maxTempC}°C，禁止出库")
        }
        val box = boxes.findOrCreate(req.boxNo)
        if (box.status != BoxStatus.READY) {
            throw ConflictException("保温箱 ${req.boxNo} 当前状态为 ${box.status}，不可用于出库")
        }

        // 库存锁定 → 出库扣减；医保预结算 → 正式结算。
        // 已拣货：批号须与拣货批号一致；未拣货（直接出库，兼容原流程）：有纳管库存则即时扣减，无账本则跳过。
        val now = System.currentTimeMillis()
        if (o.picked) {
            val lock = o.stockLockId?.let { stocks.findLock(it) }
                ?: throw ConflictException("订单 ${o.id} 已拣货但缺少库存锁定记录，不能出库")
            if (lock.batchNo != req.batchNo) {
                throw ConflictException("出库批号 ${req.batchNo} 与拣货锁定批号 ${lock.batchNo} 不一致，不能出库")
            }
            stocks.deduct(lock.id)
            payments.settle(orderId, operator)
        } else {
            val lock = stocks.immediateDeduct(o, req.batchNo, operator)
            payments.preSettle(orderId, operator, "直接出库，医保预结算")
            payments.settle(orderId, operator)
            o.picked = true
            o.pickedAt = now
            o.pickOperator = operator
            o.stockLockId = lock?.id
        }

        box.status = BoxStatus.IN_USE
        box.currentOrderId = o.id
        box.history.add(o.id)
        boxes.save(box)

        o.batchNo = req.batchNo
        o.expiryDate = req.expiryDate
        o.boxNo = req.boxNo
        o.icePackCount = req.icePackCount
        o.outboundTempC = req.outboundTempC
        o.estimatedDeliveryAt = now + req.etaMinutes * 60_000
        o.outboundAt = now
        o.warehouseOperator = operator
        o.status = OrderStatus.OUTBOUND
        o.usability = Usability.USABLE
        return save(o)
    }

    // ---------- 药师重新判断（出库后签收失败/超温/退药时必需） ----------

    fun rejudgment(orderId: String, req: RejudgmentRequest, pharmacist: String): ColdChainOrder {
        val o = orders.require(orderId)
        if (o.usability != Usability.PENDING_REJUDGMENT) {
            throw ConflictException("订单 ${o.id} 当前不需要药师重新判断（usability=${o.usability}）")
        }
        val rec = Rejudgment(
            id = store.nextId("RJ"),
            orderId = orderId,
            pharmacistId = pharmacist,
            usable = req.usable,
            comment = req.comment,
            createdAt = System.currentTimeMillis()
        )
        store.rpushJson("rejudgment:$orderId", rec)

        if (req.usable) {
            o.usability = Usability.USABLE
            when (o.status) {
                // 配送异常/签收失败 → 药品仍可用，继续配送
                OrderStatus.DELIVERY_EXCEPTION, OrderStatus.SIGN_FAILED -> {
                    o.status = OrderStatus.DELIVERING
                    incidents.resolveAllForOrder(orderId, pharmacist, "药师重新判断：药品可继续使用，恢复配送")
                }
                // 退药申请中药品仍可用 → 仍需客服/赔付处理退药诉求
                OrderStatus.RETURN_REQUESTED -> o.status = OrderStatus.AWAITING_RESOLUTION
                else -> {}
            }
        } else {
            o.usability = Usability.UNUSABLE_SCRAPPED
            o.status = OrderStatus.AWAITING_RESOLUTION
            incidents.report(
                orderId, IncidentType.PHARMACIST_UNUSABLE, Role.PHARMACIST, pharmacist,
                "药师判定药品不可继续使用: ${req.comment}"
            )
        }
        return save(o)
    }

    // ---------- 患者签收 / 退药 ----------

    fun sign(orderId: String, req: SignRequest, patientId: String): ColdChainOrder {
        val o = orders.require(orderId)
        if (o.patientId != patientId) throw ForbiddenException("只能签收本人订单")
        requireStatus(o, OrderStatus.DELIVERING)
        if (!req.reminderAcknowledged) throw BadRequestException("签收前必须确认已知晓用药提醒")
        if (req.idLast4.length != 4 || !req.idLast4.all { it.isDigit() }) {
            throw BadRequestException("身份证后四位格式不正确")
        }
        if (req.receiverType == ReceiverType.PROXY && req.receiverRelation.isNullOrBlank()) {
            throw BadRequestException("代收人签收必须填写与患者的关系")
        }
        // 外观破损 → 签收失败，上报异常，待药师重新判断
        if (!req.appearanceOk) {
            o.status = OrderStatus.DELIVERY_EXCEPTION
            o.usability = Usability.PENDING_REJUDGMENT
            save(o)
            incidents.report(orderId, IncidentType.PACKAGE_DAMAGED, Role.PATIENT, patientId, "患者签收时发现药品外包装破损")
            throw ConflictException("签收失败：药品外包装破损，已上报异常，等待药师重新判断药品是否可用")
        }
        // 温度标签异常 → 签收失败，疑似超温
        if (!req.tempLabelOk) {
            o.status = OrderStatus.DELIVERY_EXCEPTION
            o.usability = Usability.PENDING_REJUDGMENT
            save(o)
            incidents.report(orderId, IncidentType.OVER_TEMPERATURE, Role.PATIENT, patientId, "温度标签显示异常（变色），疑似配送途中超温")
            throw ConflictException("签收失败：温度标签异常，已上报异常，等待药师重新判断药品是否可用")
        }
        o.signOff = SignOff(
            orderId = orderId,
            receiverType = req.receiverType,
            receiverName = req.receiverName,
            receiverRelation = req.receiverRelation,
            idLast4 = req.idLast4,
            appearanceOk = true,
            tempLabelOk = true,
            reminderAcknowledged = true,
            signedBy = patientId,
            at = System.currentTimeMillis()
        )
        o.status = OrderStatus.SIGNED
        save(o)
        // 顺利签收后，关闭配送途中遗留的已处理异常
        incidents.resolveAllForOrder(orderId, patientId, "订单已签收")
        return o
    }

    fun returnRequest(orderId: String, req: ReturnRequestBody, patientId: String): ColdChainOrder {
        val o = orders.require(orderId)
        if (o.patientId != patientId) throw ForbiddenException("只能操作本人订单")
        requireStatus(o, OrderStatus.SIGNED, OrderStatus.DELIVERING)
        if (req.reason.isBlank()) throw BadRequestException("退药原因不能为空")
        o.status = OrderStatus.RETURN_REQUESTED
        o.usability = Usability.PENDING_REJUDGMENT
        o.returnReason = req.reason
        save(o)
        incidents.report(orderId, IncidentType.PATIENT_RETURN_REQUEST, Role.PATIENT, patientId, "患者申请退药: ${req.reason}")
        return o
    }

    // ---------- 视图 ----------

    /** 药店视角：按订单看到药品是否仍可使用 */
    fun pharmacyView(orderId: String): PharmacyOrderView {
        val o = orders.require(orderId)
        val telemetry = store.lrangeJson<TelemetryReading>("telemetry:$orderId")
        val summary = if (telemetry.isEmpty()) null else TelemetrySummary(
            count = telemetry.size,
            maxTempC = telemetry.maxOf { it.tempC },
            minTempC = telemetry.minOf { it.tempC },
            lastTempC = telemetry.last().tempC,
            boxOpenCount = o.boxOpenCount,
            lastLat = telemetry.lastOrNull { it.lat != null }?.lat,
            lastLng = telemetry.lastOrNull { it.lng != null }?.lng
        )
        val (returnCase, _) = returns.pharmacyView(orderId)
        return PharmacyOrderView(
            order = o,
            drugUsable = when (o.usability) {
                Usability.USABLE -> true
                Usability.UNUSABLE_SCRAPPED -> false
                else -> null
            },
            needsRejudgment = o.usability == Usability.PENDING_REJUDGMENT,
            telemetrySummary = summary,
            incidents = incidents.byOrder(orderId),
            rejudgments = store.lrangeJson("rejudgment:$orderId"),
            resolution = store.get<Resolution>("resolution:$orderId"),
            payment = payments.get(orderId),
            box = o.boxNo?.let { boxes.find(it) },
            followUps = store.lrangeJson("followup:$orderId"),
            stockLock = o.stockLockId?.let { stocks.findLock(it) } ?: stocks.activeLock(orderId),
            settlement = payments.settlementInfo(orderId),
            returnCase = returnCase
        )
    }

    /** 患者视角：超温、补发、退款、用药提醒的当前状态 */
    fun patientView(orderId: String, patientId: String): PatientOrderView {
        val o = orders.require(orderId)
        if (o.patientId != patientId) throw ForbiddenException("只能查看本人订单")
        val payment = payments.get(orderId)
        val telemetry = store.lrangeJson<TelemetryReading>("telemetry:$orderId")
        val reissued = o.reissuedOrderId?.let { orders.find(it) }
        return PatientOrderView(
            orderId = o.id,
            status = o.status,
            drugName = o.drugName,
            drugType = o.drugType,
            overTemp = OverTempInfo(
                occurred = o.overTemp,
                maxTempC = telemetry.maxOfOrNull { it.tempC },
                allowedRange = "${o.drugType.minTempC}-${o.drugType.maxTempC}°C"
            ),
            reissue = ReissueInfo(
                requested = o.reissuedOrderId != null,
                newOrderId = o.reissuedOrderId,
                newOrderStatus = reissued?.status
            ),
            refund = RefundInfo(
                status = payment?.refundStatus ?: RefundStatus.NONE,
                refundedSelfPayCents = payment?.refundedSelfPayCents ?: 0,
                refundedTotalCents = (payment?.refundedInsuranceCents ?: 0) + (payment?.refundedSelfPayCents ?: 0)
            ),
            medicationReminders = o.medicationReminders,
            riskReminders = o.riskReminders,
            sign = o.signOff,
            estimatedDeliveryAt = o.estimatedDeliveryAt,
            prescriptionReturn = returns.patientView(orderId, patientId)
        )
    }

    // ---------- 工具 ----------

    fun save(o: ColdChainOrder): ColdChainOrder {
        o.updatedAt = System.currentTimeMillis()
        orders.save(o)
        return o
    }

    fun requireStatus(o: ColdChainOrder, vararg allowed: OrderStatus) {
        if (o.status !in allowed) {
            throw ConflictException("订单 ${o.id} 当前状态为 ${o.status}，不允许该操作（需要: ${allowed.joinToString()}）")
        }
    }

    companion object {
        fun medicationRemindersFor(drugType: DrugType): List<String> = when (drugType) {
            DrugType.INSULIN -> listOf(
                "胰岛素未开封需 2-8°C 冷藏，禁止冷冻",
                "开封后可在室温（≤25°C）保存 4 周",
                "注射前检查药液是否澄清，如有絮状物请勿使用",
                "注射部位需轮换，避免皮下硬结"
            )
            DrugType.BIOLOGICAL -> listOf(
                "生物制剂需 2-8°C 冷藏，避免震荡",
                "使用前提前 30 分钟取出回温，切勿加热",
                "如出现过敏反应请立即就医"
            )
            DrugType.EYE_DROPS -> listOf(
                "滴眼液需 2-8°C 冷藏保存",
                "开封后 4 周内用完",
                "滴药前洗手，瓶口勿接触眼睛"
            )
            DrugType.SPECIAL_COLD_CHAIN -> listOf(
                "全程 2-8°C 冷链保存",
                "如有温度异常记录请勿使用并联系药师"
            )
        }
    }
}
