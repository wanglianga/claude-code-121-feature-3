package com.pharmacy.coldchain.domain

// ---------- 请求 DTO ----------

data class CreateOrderRequest(
    val patientId: String,
    val patientName: String,
    val drugType: DrugType,
    val drugName: String,
    val quantity: Int = 1,
    val prescriptionNo: String,
    val totalAmountCents: Long,
    val insuranceAmountCents: Long
)

data class ReviewRequest(
    val approved: Boolean,
    val comment: String? = null
)

data class OutboundRequest(
    val batchNo: String,
    val expiryDate: String,   // YYYY-MM-DD
    val boxNo: String,
    val icePackCount: Int,
    val outboundTempC: Double,
    val etaMinutes: Long
)

data class TelemetryRequest(
    val tempC: Double,
    val lat: Double? = null,
    val lng: Double? = null,
    val boxOpened: Boolean = false,
    val detour: Boolean = false,
    val deliveryStatus: String? = null
)

data class DeliveryAttemptRequest(
    val result: String   // 目前支持 PATIENT_NOT_HOME
)

data class SignRequest(
    val receiverType: ReceiverType,
    val receiverName: String,
    val receiverRelation: String? = null,
    val idLast4: String,
    val appearanceOk: Boolean,
    val tempLabelOk: Boolean,
    val reminderAcknowledged: Boolean
)

data class ReturnRequestBody(val reason: String)

data class RejudgmentRequest(
    val usable: Boolean,
    val comment: String
)

data class IncidentRequest(
    val type: IncidentType,
    val detail: String
)

data class ResolveIncidentRequest(val note: String)

data class ResolutionRequest(
    val drugDisposition: DrugDisposition,
    val reissue: Boolean = false,
    val refundAmountCents: Long = 0,
    val riderLiability: RiderLiability = RiderLiability.NONE,
    val riderFineCents: Long = 0,
    val riskReminder: String? = null
)

data class RefundRequest(val amountCents: Long)

data class PhotoRequest(
    val url: String,
    val note: String? = null
)

/** 拣货请求（仓库在复核通过、出库前拣货并锁定库存） */
data class PickStockRequest(
    val batchNo: String,
    val pickedBy: String? = null
)

/** 药师处方退回联动请求（剂量/适应症不符，暂停出库并通知患者补充材料） */
data class PrescriptionReturnRequest(
    val reason: PrescriptionReturnReason,
    val detail: String,
    val requiredMaterials: List<String> = emptyList()
)

/** 患者补充材料请求 */
data class SupplementRequest(
    val note: String? = null,
    val photos: List<PhotoRequest> = emptyList()
)

/** 药师重新审核请求（补充完成后） */
data class ReReviewRequest(
    val approved: Boolean,
    val comment: String? = null
)

data class RecycleRequest(
    val conditionOk: Boolean,
    val note: String? = null
)

data class TrainingRequest(
    val course: String,
    val score: Int,
    val passed: Boolean
)

data class FollowUpRequest(
    val result: FollowUpResult,
    val note: String? = null
)

// ---------- 视图 DTO ----------

data class TelemetrySummary(
    val count: Int,
    val maxTempC: Double,
    val minTempC: Double,
    val lastTempC: Double,
    val boxOpenCount: Int,
    val lastLat: Double?,
    val lastLng: Double?
)

/** 药店视角：按订单看到药品是否仍可使用，以及全过程记录 */
data class PharmacyOrderView(
    val order: ColdChainOrder,
    val drugUsable: Boolean?,        // true 可用 / false 不可用 / null 未出库或待判断
    val needsRejudgment: Boolean,    // 是否需要药师重新判断
    val telemetrySummary: TelemetrySummary?,
    val incidents: List<Incident>,
    val rejudgments: List<Rejudgment>,
    val resolution: Resolution?,
    val payment: PaymentSplit?,
    val box: ColdChainBox?,
    val followUps: List<FollowUp>,
    val inventoryLock: InventoryLock?,                    // 库存锁定（含回滚/重新锁定时间）
    val prescriptionReturns: List<PrescriptionReturn>,    // 处方退回联动记录
    val pharmacistAudit: List<PharmacistAuditEntry>        // 药师留痕
)

data class OverTempInfo(
    val occurred: Boolean,
    val maxTempC: Double?,
    val allowedRange: String
)

data class ReissueInfo(
    val requested: Boolean,
    val newOrderId: String?,
    val newOrderStatus: OrderStatus?
)

data class RefundInfo(
    val status: RefundStatus,
    val refundedSelfPayCents: Long,
    val refundedTotalCents: Long
)

/** 患者端：处方退回联动与恢复出库条件 */
data class PrescriptionReturnInfo(
    val active: Boolean,                          // 当前是否处于退回联动流程中
    val returnId: String?,
    val reason: PrescriptionReturnReason?,
    val detail: String?,
    val status: PrescriptionReturnStatus?,
    val requiredMaterials: List<String>,         // 需要补充的材料
    val rollback: ReturnRollbackInfo?,           // 已拣货资源回滚情况
    val resumeConditions: List<ResumeCondition>, // 恢复出库需满足的条件及满足情况
    val allConditionsMet: Boolean,               // 条件是否已全部满足
    val inventoryLockStatus: InventoryLockStatus?,
    val settlementStatus: SettlementStatus?
)

data class ReturnRollbackInfo(
    val drugPicked: Boolean,
    val inventoryRolledBack: Boolean,
    val inventoryRolledBackAt: Long?,
    val settlementRolledBack: Boolean,
    val settlementRolledBackAt: Long?
)

/** 恢复出库的单个条件及当前是否满足 */
data class ResumeCondition(
    val text: String,
    val met: Boolean
)

/** 患者视角：超温、补发、退款、用药提醒的当前状态 */
data class PatientOrderView(
    val orderId: String,
    val status: OrderStatus,
    val drugName: String,
    val drugType: DrugType,
    val overTemp: OverTempInfo,
    val reissue: ReissueInfo,
    val refund: RefundInfo,
    val medicationReminders: List<String>,
    val riskReminders: List<String>,
    val sign: SignOff?,
    val estimatedDeliveryAt: Long?,
    val prescriptionReturn: PrescriptionReturnInfo,
    val notifications: List<PatientNotification>
)

/** 赔付处理建议（根据异常事件自动推导） */
data class ResolutionSuggestion(
    val orderId: String,
    val suggestedDrugDisposition: DrugDisposition,
    val suggestedReissue: Boolean,
    val suggestedRefundCents: Long,
    val suggestedRiderLiability: RiderLiability,
    val suggestedRiskReminder: String?,
    val openIncidentTypes: List<IncidentType>
)
