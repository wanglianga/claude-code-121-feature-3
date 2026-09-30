package com.pharmacy.coldchain.domain

/** 冷藏药品配送订单（Redis 中以 JSON 单文档存储于 order:{id}） */
data class ColdChainOrder(
    val id: String,
    val patientId: String,
    val patientName: String,
    val drugType: DrugType,
    val drugName: String,
    val quantity: Int,
    val prescriptionNo: String,
    val totalAmountCents: Long,
    val insuranceAmountCents: Long,
    val selfPayAmountCents: Long,
    var status: OrderStatus = OrderStatus.PRESCRIPTION_REVIEWING,
    var usability: Usability = Usability.NOT_OUTBOUND,

    // 处方审核
    var prescriptionReviewer: String? = null,
    var prescriptionReviewComment: String? = null,
    var prescriptionReviewedAt: Long? = null,

    // 药师复核
    var pharmacistReviewer: String? = null,
    var pharmacistReviewComment: String? = null,
    var pharmacistReviewedAt: Long? = null,

    // 拣货（已拣货未出库：库存锁定 + 医保预结算）
    var picked: Boolean = false,
    var pickedAt: Long? = null,
    var pickOperator: String? = null,
    var stockLockId: String? = null,

    // 处方退回联动
    var returnCaseId: String? = null,
    var outboundPausedAt: Long? = null,
    var resumeConfirmedAt: Long? = null,
    var resumeConfirmedBy: String? = null,

    // 出库记录
    var batchNo: String? = null,
    var expiryDate: String? = null,          // YYYY-MM-DD
    var boxNo: String? = null,
    var icePackCount: Int? = null,
    var outboundTempC: Double? = null,
    var estimatedDeliveryAt: Long? = null,
    var outboundAt: Long? = null,
    var warehouseOperator: String? = null,

    // 配送过程
    var riderId: String? = null,
    var pickedUpAt: Long? = null,
    var boxOpenCount: Int = 0,
    var lastTempC: Double? = null,
    var overTemp: Boolean = false,
    var lastDeliveryStatus: String? = null,

    // 签收
    var signOff: SignOff? = null,

    // 退药
    var returnReason: String? = null,

    // 补发关联
    var reissueOfOrderId: String? = null,
    var reissuedOrderId: String? = null,

    // 用药提醒（按药品类型生成）与用药风险提醒（赔付/药师追加）
    val medicationReminders: List<String> = emptyList(),
    val riskReminders: MutableList<String> = mutableListOf(),

    val createdAt: Long = 0,
    var updatedAt: Long = 0
)

/** 签收记录 */
data class SignOff(
    val orderId: String,
    val receiverType: ReceiverType,   // SELF 本人 / PROXY 代收人
    val receiverName: String,
    val receiverRelation: String? = null, // 代收人与患者关系
    val idLast4: String,              // 身份证后四位
    val appearanceOk: Boolean,        // 药品外观完好
    val tempLabelOk: Boolean,         // 温度标签正常
    val reminderAcknowledged: Boolean,// 已知晓用药提醒
    val signedBy: String,             // 操作的患者账号
    val at: Long
)

/** 配送遥测（箱内温度 / 路线 / 开箱 / 配送状态），Redis list: telemetry:{orderId} */
data class TelemetryReading(
    val orderId: String,
    val riderId: String,
    val tempC: Double,
    val lat: Double? = null,
    val lng: Double? = null,
    val boxOpened: Boolean = false,
    val detour: Boolean = false,
    val deliveryStatus: String? = null,
    val at: Long
)

/** 异常事件，把各角色连接到同一订单 */
data class Incident(
    val id: String,
    val orderId: String,
    val type: IncidentType,
    val reporterRole: String,
    val reporterId: String,
    val detail: String,
    var status: IncidentStatus = IncidentStatus.OPEN,
    var resolutionNote: String? = null,
    var resolvedBy: String? = null,
    val createdAt: Long,
    var resolvedAt: Long? = null
)

/** 药师重新判断记录（出库后签收失败/超温/退药时必填） */
data class Rejudgment(
    val id: String,
    val orderId: String,
    val pharmacistId: String,
    val usable: Boolean,
    val comment: String,
    val createdAt: Long
)

/** 最终处理结果：报损/补发/退款/骑手责任/用药风险提醒 */
data class Resolution(
    val orderId: String,
    val drugDisposition: DrugDisposition,
    val reissue: Boolean,
    val reissueOrderId: String? = null,
    val refundAmountCents: Long = 0,
    val insuranceRefundCents: Long = 0,
    val selfPayRefundCents: Long = 0,
    val riderLiability: RiderLiability = RiderLiability.NONE,
    val riderFineCents: Long = 0,
    val riskReminder: String? = null,
    val resolvedBy: String,
    val createdAt: Long
)

/** 医保/自费拆分及退款进度 */
data class PaymentSplit(
    val orderId: String,
    val totalCents: Long,
    val insuranceCents: Long,
    val selfPayCents: Long,
    var refundedInsuranceCents: Long = 0,
    var refundedSelfPayCents: Long = 0,
    var refundStatus: RefundStatus = RefundStatus.NONE,
    var settlementStatus: SettlementStatus = SettlementStatus.NONE
)

/** 冷链保温箱（循环使用） */
data class ColdChainBox(
    val boxNo: String,
    var status: BoxStatus = BoxStatus.READY,
    var timesUsed: Int = 0,
    var currentOrderId: String? = null,
    val history: MutableList<String> = mutableListOf(),
    var conditionNote: String? = null
)

/** 骑手冷链培训记录 */
data class TrainingRecord(
    val riderId: String,
    val course: String,
    val score: Int,
    val passed: Boolean,
    val at: Long
)

/** 患者用药回访 */
data class FollowUp(
    val id: String,
    val orderId: String,
    val staffId: String,
    val result: FollowUpResult,
    val note: String? = null,
    val at: Long
)

/** 处方照片归档 */
data class PrescriptionPhoto(
    val id: String,
    val orderId: String,
    val url: String,
    val uploadedBy: String,
    val note: String? = null,
    val at: Long
)

// ---------- 处方退回联动：库存 / 医保结算 / 患者通知 / 退回 case ----------

/** 库存商品（按药品名 + 批号维度维护可用库存） */
data class StockItem(
    val drugName: String,
    val batchNo: String,
    var availableQty: Int = 0
)

/**
 * 库存锁定记录（拣货锁定 → 退回回滚释放 / 出库扣减）。
 * 重新审核后按新记录重锁，lockNo 递增，锁定时间随之更新。
 */
data class StockLock(
    val id: String,
    val orderId: String,
    val drugName: String,
    val batchNo: String,
    val qty: Int,
    var status: StockLockStatus,
    val lockedAt: Long,
    var releasedAt: Long? = null,
    var releaseReason: String? = null,
    val lockNo: Int = 1,
    val operator: String
)

/** 医保结算事件流水（预结算 / 回滚 / 重新预结算 / 正式结算） */
data class SettlementEvent(
    val id: String,
    val orderId: String,
    val action: SettlementStatus,   // PRE_SETTLED / ROLLED_BACK / SETTLED
    val insuranceCents: Long,
    val selfPayCents: Long,
    val operator: String,
    val note: String?,
    val at: Long
)

/** 患者通知（退回补充 / 恢复出库），撤回时状态置 RECALLED */
data class PatientNotification(
    val id: String,
    val orderId: String,
    val patientId: String,
    val type: NotificationType,
    val title: String,
    val content: String,
    var status: NotificationStatus = NotificationStatus.SENT,
    val sentBy: String,
    val sentAt: Long,
    var recalledAt: Long? = null
)

/** 患者补充的处方材料 */
data class SupplementMaterial(
    val id: String,
    val orderId: String,
    val url: String,
    val note: String?,
    val uploadedBy: String,
    val at: Long
)

/**
 * 处方退回联动 case（药师留痕的主体）：
 * 一次"退回 → 补充 → 重审 → 确认恢复"的完整记录，包含库存/医保/通知三项联动结果。
 */
data class PrescriptionReturnCase(
    val id: String,
    val orderId: String,
    var status: ReturnCaseStatus,

    // 退回
    val reason: ReturnReason,
    val reasonDetail: String?,
    val initiatedBy: String,
    val initiatedAt: Long,
    val pickedWhenReturned: Boolean,    // 退回时是否已拣货（决定是否触发三项回滚）

    // 回滚结果
    var rolledBackLockId: String? = null,
    var lockReleasedAt: Long? = null,
    var settlementRolledBackAt: Long? = null,
    var returnNotificationId: String? = null,

    // 患者补充
    val supplements: MutableList<SupplementMaterial> = mutableListOf(),
    var resuppliedAt: Long? = null,

    // 重新审核
    var reReviewedAt: Long? = null,
    var reReviewedBy: String? = null,
    var reReviewComment: String? = null,
    var relockId: String? = null,
    var relockAt: Long? = null,
    var reSettledAt: Long? = null,
    var resumeNotificationId: String? = null,

    // 药师再次确认恢复
    var resumeConfirmedBy: String? = null,
    var resumeConfirmedAt: Long? = null,
    var resumeCondition: String? = null,

    val auditTrail: MutableList<ReturnAuditEntry> = mutableListOf()
)

/** 退回联动操作留痕条目 */
data class ReturnAuditEntry(
    val action: String,
    val operator: String,
    val detail: String,
    val at: Long
)
