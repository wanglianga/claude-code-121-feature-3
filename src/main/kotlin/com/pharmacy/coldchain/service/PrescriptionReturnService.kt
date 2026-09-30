package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.NotificationType
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.PrescriptionReturnCase
import com.pharmacy.coldchain.domain.PrescriptionReturnRequest
import com.pharmacy.coldchain.domain.PrescriptionReturnView
import com.pharmacy.coldchain.domain.ReReviewRequest
import com.pharmacy.coldchain.domain.ResumeConfirmRequest
import com.pharmacy.coldchain.domain.ReturnCaseStatus
import com.pharmacy.coldchain.domain.ReturnReason
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.SettlementInfo
import com.pharmacy.coldchain.domain.StockLock
import com.pharmacy.coldchain.domain.SupplementMaterial
import com.pharmacy.coldchain.domain.SupplementRequest
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.OrderRepository
import com.pharmacy.coldchain.repo.get
import org.springframework.stereotype.Service

/**
 * 处方退回联动编排：
 * 药师审核发现处方剂量或适应症不符 → 暂停出库；已拣货未出库时同步回滚库存锁定、医保结算与患者通知，
 * 并通知患者补充材料 → 患者补充 → 药师重新审核（库存重新锁定、锁定时间更新、医保重新预结算，
 * 患者看到恢复出库条件）→ 药师再次确认 → 恢复出库。全过程写退回 case 与异常事件留痕。
 */
@Service
class PrescriptionReturnService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val incidents: IncidentService,
    private val stocks: StockService,
    private val payments: PaymentService,
    private val notifications: NotificationService
) {

    private fun caseKey(caseId: String) = "returncase:$caseId"
    private fun orderCasesKey(orderId: String) = "idx:returncase:order:$orderId"

    // ---------- 药师发起退回 ----------

    fun initiate(orderId: String, req: PrescriptionReturnRequest, pharmacist: String): PrescriptionReturnCase {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.READY_FOR_OUTBOUND, OrderStatus.PICKED)
        activeCase(orderId)?.let {
            throw ConflictException("订单 $orderId 已存在进行中的处方退回 case ${it.id}，请勿重复发起")
        }
        if (req.reason !in listOf(ReturnReason.DOSAGE_MISMATCH, ReturnReason.INDICATION_MISMATCH, ReturnReason.OTHER)) {
            throw BadRequestException("退回原因不合法")
        }
        val now = System.currentTimeMillis()
        val picked = o.picked
        val case = PrescriptionReturnCase(
            id = store.nextId("RC"),
            orderId = orderId,
            status = ReturnCaseStatus.OPEN,
            reason = req.reason,
            reasonDetail = req.detail,
            initiatedBy = pharmacist,
            initiatedAt = now,
            pickedWhenReturned = picked
        )

        // 1) 暂停出库
        o.status = OrderStatus.OUTBOUND_PAUSED
        o.returnCaseId = case.id
        o.outboundPausedAt = now

        // 2) 已拣货未出库：库存锁定、医保结算、患者通知同步回滚
        if (picked) {
            val released = stocks.releaseForReturn(orderId, "处方退回（${req.reason.label}），回滚库存锁定")
            case.rolledBackLockId = released?.id
            case.lockReleasedAt = released?.releasedAt
            payments.rollbackSettlement(orderId, pharmacist, "处方退回（${req.reason.label}），医保预结算回滚")
                ?.let { case.settlementRolledBackAt = it.at }
            notifications.recallByType(orderId, NotificationType.PICKED_READY)
            case.audit(
                "ROLLBACK", pharmacist,
                "已拣货未出库：库存锁定 ${released?.id} 释放回库、医保预结算回滚、拣货通知撤回"
            )
        } else {
            case.audit("PAUSE", pharmacist, "尚未拣货：仅暂停出库，无库存/医保/通知需回滚")
        }

        // 3) 通知患者补充材料
        val n = notifications.send(
            orderId, o.patientId, NotificationType.PRESCRIPTION_RETURNED,
            "您的处方被退回，需补充材料",
            "药师审核发现处方${req.reason.label}${req.detail?.let { "：$it" } ?: ""}，出库已暂停，" +
                "请尽快补充处方/诊断证明等材料后重新送审。",
            pharmacist
        )
        case.returnNotificationId = n.id

        // 4) 异常事件留痕（把药师、仓库、医保、患者连到同一订单）
        val inc = incidents.report(
            orderId, com.pharmacy.coldchain.domain.IncidentType.PRESCRIPTION_RETURNED,
            Role.PHARMACIST, pharmacist,
            "药师发起处方退回（${req.reason.label}）${if (picked) "，已拣货未出库，库存锁定/医保结算/患者通知同步回滚" else "，尚未拣货"}" +
                req.detail?.let { "：$it" }.orEmpty()
        )
        case.audit("INITIATE", pharmacist, "发起处方退回，暂停出库；异常事件 ${inc.id}")

        saveCase(case)
        save(o)
        return case
    }

    // ---------- 患者补充材料 ----------

    fun supplement(orderId: String, req: SupplementRequest, patientId: String): PrescriptionReturnCase {
        val o = orders.require(orderId)
        if (o.patientId != patientId) throw ForbiddenException("只能为本人订单补充材料")
        if (req.url.isBlank()) throw BadRequestException("补充材料地址不能为空")
        val case = activeCase(orderId) ?: throw ConflictException("订单 $orderId 当前没有进行中的处方退回，无需补充材料")
        if (case.status != ReturnCaseStatus.OPEN) {
            throw ConflictException("退回 case ${case.id} 当前状态为 ${case.status}，不能再补充材料")
        }
        val material = SupplementMaterial(
            id = store.nextId("SM"),
            orderId = orderId,
            url = req.url,
            note = req.note,
            uploadedBy = patientId,
            at = System.currentTimeMillis()
        )
        case.supplements.add(material)
        case.status = ReturnCaseStatus.RESUPPLIED
        case.resuppliedAt = material.at
        case.audit("SUPPLEMENT", patientId, "患者补充材料 ${material.url}${req.note?.let { "：$it" } ?: ""}")
        saveCase(case)
        return case
    }

    // ---------- 药师重新审核 ----------

    fun reReview(orderId: String, req: ReReviewRequest, pharmacist: String): PrescriptionReturnCase {
        val o = orders.require(orderId)
        val case = activeCase(orderId) ?: throw ConflictException("订单 $orderId 当前没有进行中的处方退回")
        if (case.status != ReturnCaseStatus.RESUPPLIED) {
            throw ConflictException("退回 case ${case.id} 当前状态为 ${case.status}，需患者先补充材料再重新审核")
        }
        if (case.supplements.isEmpty()) throw ConflictException("尚未收到患者补充材料，不能重新审核")

        if (!req.approved) {
            // 重审不通过：继续暂停，退回等待补充状态
            case.status = ReturnCaseStatus.OPEN
            case.audit("RE_REVIEW_REJECTED", pharmacist, "重新审核不通过：${req.comment ?: "未说明原因"}，请患者继续补充")
            notifications.send(
                orderId, o.patientId, NotificationType.PRESCRIPTION_RETURNED,
                "补充材料仍未通过审核",
                "您补充的材料未通过药师审核：${req.comment ?: "未说明原因"}，请继续补充后重新送审。",
                pharmacist
            )
            saveCase(case)
            return case
        }

        // 重审通过：库存重新锁定（锁定时间更新）、医保重新预结算
        if (case.pickedWhenReturned) {
            val batchNo = case.rolledBackLockId?.let { store.get<StockLock>("lock:$it")?.batchNo }
                ?: stocks.locksOf(orderId).lastOrNull { it.batchNo.isNotBlank() }?.batchNo
                ?: throw ConflictException("无法确定原拣货批号，不能重新锁定库存")
            val relock = stocks.lock(o, batchNo, pharmacist)
            case.relockId = relock.id
            case.relockAt = relock.lockedAt
            // 订单指向最新锁定，锁定时间同步更新
            o.stockLockId = relock.id
            o.pickedAt = relock.lockedAt
            val ev = payments.preSettle(orderId, pharmacist, "补充材料重审通过，医保额度重新预冻结")
            case.reSettledAt = ev.at
            case.audit("RE_LOCK", pharmacist, "重新审核通过：库存按批号 $batchNo 重新锁定 ${relock.id}，医保重新预结算")
        } else {
            case.audit("RE_REVIEW_PASSED", pharmacist, "重新审核通过（未拣货，无需重锁/预结算）")
        }

        case.status = ReturnCaseStatus.RE_REVIEWED
        case.reReviewedAt = System.currentTimeMillis()
        case.reReviewedBy = pharmacist
        case.reReviewComment = req.comment
        o.status = OrderStatus.RESUME_CONFIRMATION_PENDING

        val condition = req.comment ?: "处方补充材料已审核通过，可恢复出库"
        notifications.send(
            orderId, o.patientId, NotificationType.RE_REVIEW_PASSED,
            "处方补充材料审核通过",
            "您补充的材料已通过药师重新审核，恢复出库条件：$condition。药师最终确认后将恢复出库。",
            pharmacist
        )
        case.audit("RE_REVIEW_PASSED", pharmacist, "重新审核通过，等待药师再次确认恢复出库；恢复条件：$condition")
        saveCase(case)
        save(o)
        return case
    }

    // ---------- 药师再次确认，恢复出库 ----------

    fun confirmResume(orderId: String, req: ResumeConfirmRequest, pharmacist: String): PrescriptionReturnCase {
        val o = orders.require(orderId)
        val case = activeCase(orderId) ?: throw ConflictException("订单 $orderId 当前没有待确认的处方退回")
        if (case.status != ReturnCaseStatus.RE_REVIEWED || o.status != OrderStatus.RESUME_CONFIRMATION_PENDING) {
            throw ConflictException("退回 case ${case.id} 当前状态为 ${case.status}，尚未到恢复出库确认环节")
        }

        val now = System.currentTimeMillis()
        val condition = req.condition ?: case.reReviewComment ?: "处方补充材料已审核通过"
        case.resumeConfirmedBy = pharmacist
        case.resumeConfirmedAt = now
        case.resumeCondition = condition
        case.status = ReturnCaseStatus.RESUMED

        // 恢复出库：已拣货回到 PICKED（库存已重锁）；未拣货回到待出库
        o.status = if (case.pickedWhenReturned) OrderStatus.PICKED else OrderStatus.READY_FOR_OUTBOUND
        o.resumeConfirmedAt = now
        o.resumeConfirmedBy = pharmacist

        notifications.send(
            orderId, o.patientId, NotificationType.OUTBOUND_RESUMED,
            "订单已恢复出库",
            "药师已确认，您的订单恢复出库（条件：$condition），药品将按冷链要求尽快配送。",
            pharmacist
        )
        // 关闭本次退回产生的异常事件
        incidents.byOrder(orderId)
            .filter { it.type == com.pharmacy.coldchain.domain.IncidentType.PRESCRIPTION_RETURNED && it.status == com.pharmacy.coldchain.domain.IncidentStatus.OPEN }
            .forEach { incidents.resolve(it.id, pharmacist, "补充材料重审通过，药师确认恢复出库：$condition") }
        // 生成并关闭恢复出库事件，形成退回→恢复闭环
        val resumedIncident = incidents.report(
            orderId, com.pharmacy.coldchain.domain.IncidentType.PRESCRIPTION_RESUMED,
            Role.PHARMACIST, pharmacist,
            "处方补充材料重审通过，药师再次确认恢复出库（条件：$condition）",
            dedupOpen = false
        )
        incidents.resolve(resumedIncident.id, pharmacist, "恢复出库")

        case.audit("RESUME_CONFIRMED", pharmacist, "药师再次确认，恢复出库；条件：$condition")
        saveCase(case)
        save(o)
        return case
    }

    // ---------- 查询 / 视图 ----------

    fun getCase(caseId: String): PrescriptionReturnCase =
        store.get<PrescriptionReturnCase>(caseKey(caseId)) ?: throw NotFoundException("处方退回 case $caseId 不存在")

    fun byOrder(orderId: String): List<PrescriptionReturnCase> =
        store.smembers(orderCasesKey(orderId)).mapNotNull { store.get<PrescriptionReturnCase>(caseKey(it)) }
            .sortedBy { it.initiatedAt }

    /** 订单当前进行中的退回 case（未 RESUMED/CANCELLED） */
    fun activeCase(orderId: String): PrescriptionReturnCase? =
        byOrder(orderId).lastOrNull {
            it.status != ReturnCaseStatus.RESUMED && it.status != ReturnCaseStatus.CANCELLED
        }

    /** 患者端视图：暂停/补充/恢复条件、库存锁定时间、医保结算状态、通知 */
    fun patientView(orderId: String, patientId: String): PrescriptionReturnView? {
        val o = orders.require(orderId)
        if (o.patientId != patientId) throw ForbiddenException("只能查看本人订单")
        val case = activeCase(orderId) ?: byOrder(orderId).lastOrNull { it.status == ReturnCaseStatus.RESUMED }
            ?: return null
        return buildView(o.id, o.status, case)
    }

    fun pharmacyView(orderId: String): Pair<PrescriptionReturnCase?, PrescriptionReturnView?> {
        orders.require(orderId)
        val case = activeCase(orderId) ?: byOrder(orderId).lastOrNull { it.status == ReturnCaseStatus.RESUMED }
        return case?.let { it to buildView(orderId, orders.require(orderId).status, it) } ?: (null to null)
    }

    private fun buildView(orderId: String, orderStatus: OrderStatus, case: PrescriptionReturnCase): PrescriptionReturnView {
        val paused = orderStatus == OrderStatus.OUTBOUND_PAUSED || orderStatus == OrderStatus.RESUME_CONFIRMATION_PENDING
        val settlement: SettlementInfo? = payments.settlementInfo(orderId)
        val lock: StockLock? = stocks.activeLock(orderId)
            ?: case.relockId?.let { store.get<StockLock>("lock:$it") }
            ?: case.rolledBackLockId?.let { store.get<StockLock>("lock:$it") }
        val related = notifications.byOrder(orderId).filter {
            it.type in setOf(
                NotificationType.PRESCRIPTION_RETURNED,
                NotificationType.RE_REVIEW_PASSED,
                NotificationType.OUTBOUND_RESUMED,
                NotificationType.PICKED_READY
            )
        }
        val condition = when (case.status) {
            ReturnCaseStatus.RE_REVIEWED, ReturnCaseStatus.RESUMED ->
                case.resumeCondition ?: case.reReviewComment
            else -> null
        }
        return PrescriptionReturnView(
            caseId = case.id,
            status = case.status,
            reason = case.reason,
            reasonLabel = case.reason.label,
            reasonDetail = case.reasonDetail,
            pickedWhenReturned = case.pickedWhenReturned,
            outboundPaused = paused,
            needSupplement = case.status == ReturnCaseStatus.OPEN,
            resumeCondition = condition,
            canResumeOutbound = case.status == ReturnCaseStatus.RE_REVIEWED,
            supplements = case.supplements.toList(),
            stockLock = lock,
            settlement = settlement,
            notifications = related,
            initiatedAt = case.initiatedAt,
            resumedAt = case.resumeConfirmedAt
        )
    }

    private fun saveCase(case: PrescriptionReturnCase) {
        store.set(caseKey(case.id), case)
        store.sadd(orderCasesKey(case.orderId), case.id)
    }

    private fun save(o: ColdChainOrder): ColdChainOrder {
        o.updatedAt = System.currentTimeMillis()
        orders.save(o)
        return o
    }

    private fun requireStatus(o: ColdChainOrder, vararg allowed: OrderStatus) {
        if (o.status !in allowed) {
            throw ConflictException("订单 ${o.id} 当前状态为 ${o.status}，不允许该操作（需要: ${allowed.joinToString()}）")
        }
    }

    private fun PrescriptionReturnCase.audit(action: String, operator: String, detail: String) {
        auditTrail.add(
            com.pharmacy.coldchain.domain.ReturnAuditEntry(
                action = action, operator = operator, detail = detail, at = System.currentTimeMillis()
            )
        )
    }
}
