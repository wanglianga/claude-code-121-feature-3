package com.pharmacy.coldchain.domain

/** 冷藏药品类型，均要求 2-8°C 冷链 */
enum class DrugType(val minTempC: Double, val maxTempC: Double, val label: String) {
    INSULIN(2.0, 8.0, "胰岛素"),
    BIOLOGICAL(2.0, 8.0, "生物制剂"),
    EYE_DROPS(2.0, 8.0, "眼药水"),
    SPECIAL_COLD_CHAIN(2.0, 8.0, "特殊冷藏药品")
}

enum class OrderStatus {
    PRESCRIPTION_REVIEWING,  // 处方审核中
    PRESCRIPTION_REJECTED,   // 处方审核退回（审核前发现材料问题，可补充材料后重新送审）
    PHARMACIST_REVIEWING,    // 药师复核中
    READY_FOR_OUTBOUND,      // 待出库
    PRESCRIPTION_RETURNED,   // 处方退回联动：药师审核发现剂量/适应症不符，暂停出库待患者补充材料（已拣货的锁定/结算/通知同步回滚）
    AWAITING_RESUME_CONFIRM, // 补充完成并重新审核通过，待药师再次确认后恢复出库
    OUTBOUND,                // 已出库，待骑手取药
    DELIVERING,              // 配送中
    DELIVERY_EXCEPTION,      // 配送异常（超温 / 开盖异常 / 包装破损）
    SIGN_FAILED,             // 签收失败（患者不在家）
    RETURN_REQUESTED,        // 患者申请退药
    AWAITING_RESOLUTION,     // 待赔付/客服最终处理
    SIGNED,                  // 已签收
    REISSUED,                // 已补发（生成新订单）
    CLOSED                   // 已结案
}

/** 药品是否仍可使用（药店视角的核心字段） */
enum class Usability {
    NOT_OUTBOUND,        // 未出库
    USABLE,              // 可继续使用
    PENDING_REJUDGMENT,  // 出库后出现签收失败/超温/退药，待药师重新判断
    UNUSABLE_SCRAPPED    // 药师判定不可继续使用（报损）
}

enum class IncidentType {
    PRESCRIPTION_REJECTED,        // 处方审核退回（出库前材料问题）
    PRESCRIPTION_RETURNED,        // 处方退回联动：剂量/适应症不符，暂停出库，已拣货资源同步回滚
    OVER_TEMPERATURE,             // 超温
    RIDER_DETOUR_OVERTEMP,        // 骑手绕路导致超温
    PATIENT_NOT_HOME,             // 患者不在家
    BOX_OPENED_ABNORMAL,          // 保温箱开盖异常
    PACKAGE_DAMAGED,              // 药品外包装破损
    PATIENT_RETURN_REQUEST,       // 患者申请退药
    PHARMACIST_UNUSABLE           // 药师判定不可继续使用
}

enum class IncidentStatus { OPEN, RESOLVED }

enum class ReceiverType { SELF, PROXY }

/** 最终药品处置方式 */
enum class DrugDisposition { SCRAPPED, RETURNED_TO_STOCK }

enum class RiderLiability { NONE, PARTIAL, FULL }

enum class RefundStatus { NONE, PARTIAL, FULL }

/** 库存锁定状态（拣货即锁定，处方退回时同步回滚） */
enum class InventoryLockStatus {
    LOCKED,              // 已拣货锁定，等待出库
    ROLLED_BACK,         // 处方退回联动回滚：锁定解除、库存归还
    RELOCKED,            // 患者补充材料、药师重新审核通过后重新锁定
    RELEASED             // 已出库转实际扣减 / 正常释放
}

/** 医保预结算状态 */
enum class SettlementStatus {
    NONE,                // 尚未预结算
    PRE_SETTLED,         // 已预结算（待出库）
    ROLLED_BACK,         // 处方退回联动回滚
    RESETTLED            // 重新审核通过后重新预结算
}

/** 处方退回原因（药师审核发现） */
enum class PrescriptionReturnReason(val label: String) {
    DOSAGE_MISMATCH("处方剂量不符"),
    INDICATION_MISMATCH("适应症不符"),
    OTHER("其他")
}

/** 处方退回联动单状态 */
enum class PrescriptionReturnStatus {
    RETURNED,        // 已退回，等待患者补充材料
    SUPPLEMENTED,    // 患者已补充材料，等待药师重新审核
    RE_REVIEWED,     // 药师重新审核通过，等待再次确认恢复出库
    RESUMED          // 药师再次确认，已恢复出库
}

/** 患者通知类型 */
enum class NotificationType {
    STOCK_PICKED,           // 已拣货待出库（退回联动时该通知同步回滚/作废）
    PRESCRIPTION_RETURNED,  // 处方退回：需补充材料
    ROLLBACK_DONE,          // 已拣货资源回滚完成（库存锁定/医保结算）
    SUPPLEMENT_RECEIVED,    // 补充材料已签收
    RE_REVIEW_APPROVED,     // 重新审核通过
    READY_TO_RESUME,        // 等待药师再次确认恢复出库（患者端展示恢复条件）
    OUTBOUND_RESUMED        // 已确认恢复出库
}

enum class BoxStatus { READY, IN_USE, RETIRED }

enum class FollowUpResult { OK, ADVERSE_REACTION, NO_ANSWER }

enum class Role {
    PHARMACIST,        // 药师
    WAREHOUSE,         // 仓库
    RIDER,             // 骑手
    PATIENT,           // 患者
    CUSTOMER_SERVICE,  // 客服
    INSURANCE,         // 医保结算
    COMPENSATION,      // 赔付人员
    ADMIN
}
