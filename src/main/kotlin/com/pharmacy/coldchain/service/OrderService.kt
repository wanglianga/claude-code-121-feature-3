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
import com.pharmacy.coldchain.domain.InventoryLockStatus
import com.pharmacy.coldchain.domain.NotificationType
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.OutboundRequest
import com.pharmacy.coldchain.domain.OverTempInfo
import com.pharmacy.coldchain.domain.PatientNotification
import com.pharmacy.coldchain.domain.PatientOrderView
import com.pharmacy.coldchain.domain.PharmacistAuditEntry
import com.pharmacy.coldchain.domain.PharmacyOrderView
import com.pharmacy.coldchain.domain.PhotoRequest
import com.pharmacy.coldchain.domain.PrescriptionPhoto
import com.pharmacy.coldchain.domain.PrescriptionReturn
import com.pharmacy.coldchain.domain.PrescriptionReturnInfo
import com.pharmacy.coldchain.domain.PrescriptionReturnReason
import com.pharmacy.coldchain.domain.PrescriptionReturnRequest
import com.pharmacy.coldchain.domain.PrescriptionReturnStatus
import com.pharmacy.coldchain.domain.PickStockRequest
import com.pharmacy.coldchain.domain.ReceiverType
import com.pharmacy.coldchain.domain.RefundInfo
import com.pharmacy.coldchain.domain.RefundStatus
import com.pharmacy.coldchain.domain.ReissueInfo
import com.pharmacy.coldchain.domain.Rejudgment
import com.pharmacy.coldchain.domain.RejudgmentRequest
import com.pharmacy.coldchain.domain.Resolution
import com.pharmacy.coldchain.domain.ResumeCondition
import com.pharmacy.coldchain.domain.ReturnRequestBody
import com.pharmacy.coldchain.domain.ReturnRollbackInfo
import com.pharmacy.coldchain.domain.ReviewRequest
import com.pharmacy.coldchain.domain.ReReviewRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.SettlementStatus
import com.pharmacy.coldchain.domain.SignOff
import com.pharmacy.coldchain.domain.SignRequest
import com.pharmacy.coldchain.domain.SupplementRequest
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
    private val inventory: InventoryService,
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

    // ---------- 出库 ----------

    fun outbound(orderId: String, req: OutboundRequest, operator: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.READY_FOR_OUTBOUND)
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
        val now = System.currentTimeMillis()
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
        save(o)
        // 已出库：拣货锁定转为实际扣减并释放
        inventory.release(o.id)
        return o
    }

    // ---------- 拣货（出库前锁定库存、医保预结算） ----------

    /** 仓库拣货：锁定库存并发起医保预结算，但尚未出库、未实际收费 */
    fun pickStock(orderId: String, req: PickStockRequest, operator: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.READY_FOR_OUTBOUND)
        if (req.batchNo.isBlank()) throw BadRequestException("药品批号不能为空")
        val lock = inventory.lock(o, req.batchNo, req.pickedBy ?: operator)
        payments.preSettle(o.id)
        o.pickedAt = lock.lockedAt
        o.pickedBy = lock.lockedBy
        save(o)
        notifications.send(
            o.patientId, o.id, NotificationType.STOCK_PICKED,
            "药品已拣货，等待出库",
            "您的${o.drugName}（批号 ${req.batchNo}，数量 ${o.quantity}）已拣货锁定，医保预结算完成，等待出库配送。"
        )
        return o
    }

    // ---------- 处方退回联动（剂量/适应症不符） ----------

    /**
     * 药师审核发现处方剂量或适应症不符：暂停出库并通知患者补充材料。
     * 若药品已拣货但未出库，库存锁定、医保预结算、患者通知同步回滚。
     */
    fun prescriptionReturn(orderId: String, req: PrescriptionReturnRequest, pharmacist: String): PrescriptionReturn {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.READY_FOR_OUTBOUND, OrderStatus.AWAITING_RESUME_CONFIRM)
        if (req.detail.isBlank()) throw BadRequestException("退回说明不能为空")

        val lock = inventory.get(orderId)
        val drugPicked = lock != null &&
            (lock.status == InventoryLockStatus.LOCKED || lock.status == InventoryLockStatus.RELOCKED)

        // 先同步回滚已拣货资源（失败则整体中止，不产生退回记录）
        var rollbackText = ""
        if (drugPicked) {
            inventory.rollback(orderId, "处方退回：${req.reason.label}")
            payments.rollbackSettlement(orderId, "处方退回：${req.reason.label}")
            rollbackText = "；药品已拣货，库存锁定与医保预结算已同步回滚"
        }

        val now = System.currentTimeMillis()
        val ret = PrescriptionReturn(
            id = store.nextId("RR"),
            orderId = orderId,
            reason = req.reason,
            detail = req.detail,
            pharmacistId = pharmacist,
            drugPicked = drugPicked,
            status = PrescriptionReturnStatus.RETURNED,
            requiredMaterials = req.requiredMaterials.ifEmpty { defaultMaterials(req.reason) },
            createdAt = now
        )
        appendReturn(ret)

        o.status = OrderStatus.PRESCRIPTION_RETURNED
        o.prescriptionReturnId = ret.id
        o.returnedAt = now
        o.supplementedAt = null
        o.reReviewedAt = null
        o.resumeConfirmedAt = null
        save(o)

        incidents.report(
            orderId, IncidentType.PRESCRIPTION_RETURNED, Role.PHARMACIST, pharmacist,
            "药师审核发现${req.reason.label}，暂停出库并退回处方：${req.detail}$rollbackText",
            dedupOpen = false
        )
        audit(orderId, pharmacist, "PRESCRIPTION_RETURN",
            "处方退回（${req.reason.label}）：${req.detail}$rollbackText")

        notifications.send(
            o.patientId, orderId, NotificationType.PRESCRIPTION_RETURNED,
            "处方审核未通过：${req.reason.label}，请补充材料",
            "药师审核发现：${req.detail}。出库已暂停，请尽快补充以下材料后重新送审。",
            resumeConditions = ret.requiredMaterials
        )
        if (drugPicked) {
            notifications.send(
                o.patientId, orderId, NotificationType.ROLLBACK_DONE,
                "已拣货资源已同步回滚",
                "库存锁定已解除、医保预结算已撤销（尚未实际出库收费）；补充材料审核通过后将重新锁定并重新结算。"
            )
        }
        return ret
    }

    /** 患者补充材料（处方照片随补充材料归档），等待药师重新审核 */
    fun supplement(orderId: String, req: SupplementRequest, patientId: String): PrescriptionReturn {
        val o = orders.require(orderId)
        if (o.patientId != patientId) throw ForbiddenException("只能为本人订单补充材料")
        requireStatus(o, OrderStatus.PRESCRIPTION_RETURNED)
        val ret = latestReturn(orderId)
            ?.takeIf { it.status == PrescriptionReturnStatus.RETURNED }
            ?: throw ConflictException("订单 $orderId 当前没有待补充材料的处方退回单")
        if (req.photos.isEmpty() && req.note.isNullOrBlank()) {
            throw BadRequestException("请至少上传一份补充材料或填写补充说明")
        }

        val photoIds = req.photos.map { addPhoto(orderId, it, patientId).id }
        val now = System.currentTimeMillis()
        ret.status = PrescriptionReturnStatus.SUPPLEMENTED
        ret.supplementNote = req.note
        ret.supplementedBy = patientId
        ret.supplementedAt = now
        ret.supplementPhotoIds = ret.supplementPhotoIds + photoIds
        updateReturn(ret)

        o.supplementedAt = now
        save(o)

        notifications.send(
            o.patientId, orderId, NotificationType.SUPPLEMENT_RECEIVED,
            "补充材料已收到",
            "已收到您补充的 ${photoIds.size} 份材料${req.note?.let { "（$it）" } ?: ""}，药师将重新审核处方。"
        )
        return ret
    }

    /** 药师对补充后的处方重新审核；通过则重新锁定库存、重新医保结算，等待再次确认 */
    fun reReview(orderId: String, req: ReReviewRequest, pharmacist: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.PRESCRIPTION_RETURNED)
        val ret = latestReturn(orderId) ?: throw ConflictException("订单 $orderId 无处方退回记录")

        if (!req.approved) {
            // 仍不符合：退回单回到待补充状态，患者需继续补充材料
            ret.status = PrescriptionReturnStatus.RETURNED
            updateReturn(ret)
            audit(orderId, pharmacist, "RE_REVIEW_REJECT",
                "重新审核不通过：${req.comment ?: "未说明原因"}")
            notifications.send(
                o.patientId, orderId, NotificationType.PRESCRIPTION_RETURNED,
                "补充材料重新审核未通过，请继续补充",
                "药师复核意见：${req.comment ?: "材料仍不符合要求"}，请继续补充后重新送审。",
                resumeConditions = ret.requiredMaterials
            )
            return o
        }

        if (ret.status != PrescriptionReturnStatus.SUPPLEMENTED) {
            throw ConflictException("患者尚未补充材料（退回单状态 ${ret.status}），不能重新审核通过")
        }

        val now = System.currentTimeMillis()
        ret.status = PrescriptionReturnStatus.RE_REVIEWED
        ret.reReviewPharmacistId = pharmacist
        ret.reReviewComment = req.comment
        ret.reReviewedAt = now
        updateReturn(ret)

        // 联动恢复：库存重新锁定（锁定时间更新为重锁时刻）、医保状态更新为重新预结算
        if (ret.drugPicked) {
            inventory.relock(orderId)
            payments.preSettle(orderId)
        }

        o.status = OrderStatus.AWAITING_RESUME_CONFIRM
        o.reReviewedAt = now
        save(o)

        audit(orderId, pharmacist, "RE_REVIEW_APPROVE",
            "补充材料重新审核通过：${req.comment ?: "剂量与适应症核对无误"}" +
                if (ret.drugPicked) "；库存已重新锁定、医保已重新预结算" else "")

        notifications.send(
            o.patientId, orderId, NotificationType.RE_REVIEW_APPROVED,
            "处方重新审核通过",
            "药师已确认补充材料，处方剂量与适应症核对无误。"
        )
        notifications.send(
            o.patientId, orderId, NotificationType.READY_TO_RESUME,
            "等待药师再次确认后恢复出库",
            "恢复出库条件已基本满足，尚需药师再次确认；确认后订单将恢复出库。",
            resumeConditions = resumeConditionTexts(ret)
        )
        return o
    }

    /** 恢复出库前药师再次确认（不可由其他动作代替） */
    fun confirmResume(orderId: String, pharmacist: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.AWAITING_RESUME_CONFIRM)
        val ret = latestReturn(orderId)
            ?.takeIf { it.status == PrescriptionReturnStatus.RE_REVIEWED }
            ?: throw ConflictException("订单 $orderId 当前不处于待恢复确认状态")

        val now = System.currentTimeMillis()
        ret.status = PrescriptionReturnStatus.RESUMED
        ret.resumeConfirmedBy = pharmacist
        ret.resumeConfirmedAt = now
        updateReturn(ret)

        o.status = OrderStatus.READY_FOR_OUTBOUND
        o.resumeConfirmedAt = now
        save(o)

        incidents.resolveAllForOrder(orderId, pharmacist, "处方补充材料重新审核通过，药师再次确认恢复出库")
        audit(orderId, pharmacist, "RESUME_CONFIRM", "恢复出库前再次确认，订单恢复出库流程")
        notifications.send(
            o.patientId, orderId, NotificationType.OUTBOUND_RESUMED,
            "订单已恢复出库",
            "药师已再次确认，您的订单恢复出库流程，药品将按冷链要求配送，请保持电话畅通。"
        )
        return o
    }

    fun returns(orderId: String): List<PrescriptionReturn> =
        store.lrangeJson("rxreturn:$orderId")

    fun latestReturn(orderId: String): PrescriptionReturn? = returns(orderId).lastOrNull()

    fun auditList(orderId: String): List<PharmacistAuditEntry> =
        store.lrangeJson("audit:$orderId")

    private fun appendReturn(ret: PrescriptionReturn) {
        store.rpushJson("rxreturn:${ret.orderId}", ret)
    }

    /** 更新指定退回单（按 id 替换后整体重写 list） */
    private fun updateReturn(ret: PrescriptionReturn) {
        val all = returns(ret.orderId).toMutableList()
        val idx = all.indexOfLast { it.id == ret.id }
        if (idx >= 0) all[idx] = ret else all.add(ret)
        store.del("rxreturn:${ret.orderId}")
        all.forEach { store.rpushJson("rxreturn:${ret.orderId}", it) }
    }

    private fun audit(orderId: String, pharmacist: String, action: String, detail: String) {
        store.rpushJson(
            "audit:$orderId",
            PharmacistAuditEntry(
                id = store.nextId("AU"),
                orderId = orderId,
                pharmacistId = pharmacist,
                action = action,
                detail = detail,
                at = System.currentTimeMillis()
            )
        )
    }

    private fun resumeConditionTexts(ret: PrescriptionReturn): List<String> = buildList {
        add("患者按要求补充全部材料（${ret.requiredMaterials.size} 项）")
        add("药师对补充材料重新审核通过")
        if (ret.drugPicked) {
            add("库存按新材料核对后重新锁定")
            add("医保预结算重新确认通过")
        }
        add("药师再次确认恢复出库")
    }

    private fun defaultMaterials(reason: PrescriptionReturnReason): List<String> = when (reason) {
        PrescriptionReturnReason.DOSAGE_MISMATCH ->
            listOf("医师重新确认剂量并签章的处方原件照片", "近期用药记录或医嘱单")
        PrescriptionReturnReason.INDICATION_MISMATCH ->
            listOf("明确诊断的病历或诊断证明", "医师重新确认适应症并签章的处方原件照片")
        PrescriptionReturnReason.OTHER ->
            listOf("药师要求补充的相关证明材料")
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
            inventoryLock = inventory.get(orderId),
            prescriptionReturns = returns(orderId),
            pharmacistAudit = auditList(orderId)
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
            prescriptionReturn = prescriptionReturnInfo(o),
            notifications = notifications.forOrder(patientId, orderId)
        )
    }

    /** 患者端：处方退回联动状态、回滚情况、恢复出库条件及满足情况 */
    fun prescriptionReturnInfo(o: ColdChainOrder): PrescriptionReturnInfo {
        val ret = latestReturn(o.id)
        val activeStatuses = setOf(
            PrescriptionReturnStatus.RETURNED,
            PrescriptionReturnStatus.SUPPLEMENTED,
            PrescriptionReturnStatus.RE_REVIEWED
        )
        if (ret == null) {
            return PrescriptionReturnInfo(
                active = false, returnId = null, reason = null, detail = null, status = null,
                requiredMaterials = emptyList(), rollback = null,
                resumeConditions = emptyList(), allConditionsMet = false,
                inventoryLockStatus = inventory.get(o.id)?.status,
                settlementStatus = payments.get(o.id)?.settlementStatus
            )
        }
        val active = ret.status in activeStatuses
        val lock = inventory.get(o.id)
        val payment = payments.get(o.id)
        val supplemented = ret.status != PrescriptionReturnStatus.RETURNED
        val reReviewed = ret.status == PrescriptionReturnStatus.RE_REVIEWED ||
            ret.status == PrescriptionReturnStatus.RESUMED
        val lockReady = lock?.status == InventoryLockStatus.RELOCKED ||
            lock?.status == InventoryLockStatus.RELEASED
        val conditions = buildList {
            add(ResumeCondition("患者补充材料：" + ret.requiredMaterials.joinToString("、"), supplemented))
            add(ResumeCondition("药师重新审核通过", reReviewed))
            if (ret.drugPicked) {
                add(ResumeCondition("库存重新锁定", lockReady))
                add(ResumeCondition("医保预结算重新确认", payment?.settlementStatus == SettlementStatus.RESETTLED))
            }
            add(ResumeCondition("药师再次确认恢复出库", o.resumeConfirmedAt != null))
        }
        val rollbackInfo = if (ret.drugPicked) ReturnRollbackInfo(
            drugPicked = true,
            inventoryRolledBack = lock?.rolledBackAt != null,
            inventoryRolledBackAt = lock?.rolledBackAt,
            settlementRolledBack = payment?.settlementRolledBackAt != null,
            settlementRolledBackAt = payment?.settlementRolledBackAt
        ) else null
        return PrescriptionReturnInfo(
            active = active,
            returnId = ret.id,
            reason = ret.reason,
            detail = ret.detail,
            status = ret.status,
            requiredMaterials = ret.requiredMaterials,
            rollback = rollbackInfo,
            resumeConditions = conditions,
            allConditionsMet = conditions.all { it.met },
            inventoryLockStatus = lock?.status,
            settlementStatus = payment?.settlementStatus
        )
    }

    fun patientNotifications(patientId: String): List<PatientNotification> =
        notifications.forPatient(patientId)

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
