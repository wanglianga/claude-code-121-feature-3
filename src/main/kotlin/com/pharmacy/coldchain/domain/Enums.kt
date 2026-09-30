package com.pharmacy.coldchain.domain

/** 冷藏药品类型，均要求 2-8°C 冷链 */
enum class DrugType(val minTempC: Double, val maxTempC: Double, val label: String) {
    INSULIN(2.0, 8.0, "胰岛素"),
    BIOLOGICAL(2.0, 8.0, "生物制剂"),
    EYE_DROPS(2.0, 8.0, "眼药水"),
    SPECIAL_COLD_CHAIN(2.0, 8.0, "特殊冷藏药品")
}

enum class OrderStatus {
    PRESCRIPTION_REVIEWING,      // 处方审核中
    PRESCRIPTION_REJECTED,       // 处方审核退回（可补充材料后重新送审）
    PHARMACIST_REVIEWING,        // 药师复核中
    READY_FOR_OUTBOUND,          // 待出库
    PICKED,                      // 药品已拣货（库存已锁定），待出库
    OUTBOUND_PAUSED,             // 处方退回联动：暂停出库，等待患者补充材料
    RESUME_CONFIRMATION_PENDING, // 补充完成并重审通过，等待药师再次确认恢复出库
    OUTBOUND,                    // 已出库，待骑手取药
    DELIVERING,                  // 配送中
    DELIVERY_EXCEPTION,          // 配送异常（超温 / 开盖异常 / 包装破损）
    SIGN_FAILED,                 // 签收失败（患者不在家）
    RETURN_REQUESTED,            // 患者申请退药
    AWAITING_RESOLUTION,         // 待赔付/客服最终处理
    SIGNED,                      // 已签收
    REISSUED,                    // 已补发（生成新订单）
    CLOSED                       // 已结案
}

/** 药品是否仍可使用（药店视角的核心字段） */
enum class Usability {
    NOT_OUTBOUND,        // 未出库
    USABLE,              // 可继续使用
    PENDING_REJUDGMENT,  // 出库后出现签收失败/超温/退药，待药师重新判断
    UNUSABLE_SCRAPPED    // 药师判定不可继续使用（报损）
}

enum class IncidentType {
    PRESCRIPTION_REJECTED,        // 处方审核退回（审核阶段）
    PRESCRIPTION_RETURNED,        // 药师发起处方退回联动（剂量/适应症不符，含已拣货回滚）
    PRESCRIPTION_RESUMED,         // 补充重审后恢复出库
    OVER_TEMPERATURE,             // 超温
    RIDER_DETOUR_OVERTEMP,        // 骑手绕路导致超温
    PATIENT_NOT_HOME,             // 患者不在家
    BOX_OPENED_ABNORMAL,          // 保温箱开盖异常
    PACKAGE_DAMAGED,              // 药品外包装破损
    PATIENT_RETURN_REQUEST,       // 患者申请退药
    PHARMACIST_UNUSABLE           // 药师判定不可继续使用
}

enum class IncidentStatus { OPEN, RESOLVED }

/** 药师发起处方退回的原因 */
enum class ReturnReason(val label: String) {
    DOSAGE_MISMATCH("剂量不符"),
    INDICATION_MISMATCH("适应症不符"),
    OTHER("其他")
}

/** 处方退回联动 case 的生命周期 */
enum class ReturnCaseStatus {
    OPEN,                    // 已退回，等待患者补充材料
    RESUPPLIED,              // 患者已补充，等待药师重新审核
    RE_REVIEWED,             // 重新审核（处方审核 + 复核）通过，等待药师再次确认
    RESUMED,                 // 药师再次确认，恢复出库
    CANCELLED                // 退回后取消订单（如患者放弃）
}

/** 库存锁定状态 */
enum class StockLockStatus {
    LOCKED,      // 已拣货锁定
    RELEASED,    // 退回时回滚释放
    DEDUCTED     // 正式出库扣减
}

/** 医保结算状态（拣货预结算 → 退回回滚 → 补充重算 → 出库正式结算） */
enum class SettlementStatus {
    NONE,          // 尚未预结算（未拣货）
    PRE_SETTLED,   // 拣货后预结算（冻结额度）
    ROLLED_BACK,   // 处方退回，预结算已回滚
    SETTLED        // 正式出库结算
}

/** 患者通知状态 */
enum class NotificationStatus { SENT, RECALLED }

enum class NotificationType {
    PICKED_READY,             // 药品已拣货，即将出库（退回时需同步撤回）
    PRESCRIPTION_RETURNED,    // 处方退回，暂停出库，请补充材料
    RE_REVIEW_PASSED,         // 补充材料重审通过，待药师最终确认（含恢复出库条件）
    OUTBOUND_RESUMED          // 药师已确认，恢复出库
}

enum class ReceiverType { SELF, PROXY }

/** 最终药品处置方式 */
enum class DrugDisposition { SCRAPPED, RETURNED_TO_STOCK }

enum class RiderLiability { NONE, PARTIAL, FULL }

enum class RefundStatus { NONE, PARTIAL, FULL }

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
