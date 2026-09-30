package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.PaymentSplit
import com.pharmacy.coldchain.domain.RefundStatus
import com.pharmacy.coldchain.domain.SettlementEvent
import com.pharmacy.coldchain.domain.SettlementInfo
import com.pharmacy.coldchain.domain.SettlementStatus
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/** 医保/自费拆分、拣货预结算、退回回滚、正式结算与退款 */
@Service
class PaymentService(private val store: JsonStore) {

    private fun eventsKey(orderId: String) = "settlement:$orderId"

    fun createFor(order: ColdChainOrder): PaymentSplit {
        val split = PaymentSplit(
            orderId = order.id,
            totalCents = order.totalAmountCents,
            insuranceCents = order.insuranceAmountCents,
            selfPayCents = order.selfPayAmountCents
        )
        store.set("payment:${order.id}", split)
        return split
    }

    fun get(orderId: String): PaymentSplit? = store.get<PaymentSplit>("payment:$orderId")

    fun require(orderId: String): PaymentSplit =
        get(orderId) ?: throw NotFoundException("订单 $orderId 的支付拆分不存在")

    // ---------- 拣货预结算 / 退回回滚 / 正式结算（处方退回联动） ----------

    /** 拣货后医保预结算（冻结额度）。退回回滚后允许重新预结算（金额以订单当前拆分重算）。 */
    fun preSettle(orderId: String, operator: String, note: String? = null): SettlementEvent {
        val p = require(orderId)
        if (p.settlementStatus == SettlementStatus.PRE_SETTLED) {
            throw ConflictException("订单 $orderId 已完成医保预结算，不能重复预结算")
        }
        if (p.settlementStatus == SettlementStatus.SETTLED) {
            throw ConflictException("订单 $orderId 已正式结算，不能再预结算")
        }
        p.settlementStatus = SettlementStatus.PRE_SETTLED
        store.set("payment:$orderId", p)
        return appendEvent(orderId, SettlementStatus.PRE_SETTLED, p.insuranceCents, p.selfPayCents, operator,
            note ?: "拣货完成，医保额度预冻结")
    }

    /** 处方退回：回滚医保预结算（解冻额度） */
    fun rollbackSettlement(orderId: String, operator: String, note: String? = null): SettlementEvent? {
        val p = get(orderId) ?: return null
        if (p.settlementStatus != SettlementStatus.PRE_SETTLED) return null
        p.settlementStatus = SettlementStatus.ROLLED_BACK
        store.set("payment:$orderId", p)
        return appendEvent(orderId, SettlementStatus.ROLLED_BACK, p.insuranceCents, p.selfPayCents, operator,
            note ?: "处方退回联动：医保预结算回滚，额度解冻")
    }

    /** 正式出库：预结算转正式结算 */
    fun settle(orderId: String, operator: String, note: String? = null): SettlementEvent? {
        val p = get(orderId) ?: return null
        if (p.settlementStatus == SettlementStatus.SETTLED) return null
        if (p.settlementStatus != SettlementStatus.PRE_SETTLED) {
            throw ConflictException("订单 $orderId 当前医保结算状态为 ${p.settlementStatus}，需先拣货预结算才能出库结算")
        }
        p.settlementStatus = SettlementStatus.SETTLED
        store.set("payment:$orderId", p)
        return appendEvent(orderId, SettlementStatus.SETTLED, p.insuranceCents, p.selfPayCents, operator,
            note ?: "药品出库，医保正式结算")
    }

    fun settlementEvents(orderId: String): List<SettlementEvent> = store.lrangeJson(eventsKey(orderId))

    fun settlementInfo(orderId: String): SettlementInfo? {
        val p = get(orderId) ?: return null
        return SettlementInfo(
            status = p.settlementStatus,
            insuranceCents = p.insuranceCents,
            selfPayCents = p.selfPayCents,
            events = settlementEvents(orderId)
        )
    }

    private fun appendEvent(
        orderId: String,
        action: SettlementStatus,
        insuranceCents: Long,
        selfPayCents: Long,
        operator: String,
        note: String?
    ): SettlementEvent {
        val ev = SettlementEvent(
            id = store.nextId("SE"),
            orderId = orderId,
            action = action,
            insuranceCents = insuranceCents,
            selfPayCents = selfPayCents,
            operator = operator,
            note = note,
            at = System.currentTimeMillis()
        )
        store.rpushJson(eventsKey(orderId), ev)
        return ev
    }

    /**
     * 退款：按医保占比自动拆分为医保退回 + 自费退回。
     * 返回三元组：更新后的拆分、本次医保退款、本次自费退款。
     */
    fun issueRefund(orderId: String, amountCents: Long): Triple<PaymentSplit, Long, Long> {
        val p = require(orderId)
        val remaining = p.totalCents - p.refundedInsuranceCents - p.refundedSelfPayCents
        if (amountCents <= 0) throw BadRequestException("退款金额必须大于 0")
        if (amountCents > remaining) throw BadRequestException("退款金额 $amountCents 超过剩余可退金额 $remaining（分）")

        val insuranceRemaining = p.insuranceCents - p.refundedInsuranceCents
        val selfPayRemaining = p.selfPayCents - p.refundedSelfPayCents

        var insuranceRefund = if (p.totalCents > 0)
            Math.round(amountCents * (p.insuranceCents.toDouble() / p.totalCents.toDouble()))
        else 0L
        insuranceRefund = insuranceRefund.coerceIn(0L, insuranceRemaining)
        val selfPayRefund = amountCents - insuranceRefund
        if (selfPayRefund > selfPayRemaining) {
            throw BadRequestException("自费部分剩余可退 $selfPayRemaining 分，不足以按比例拆分，请调整退款金额")
        }

        p.refundedInsuranceCents += insuranceRefund
        p.refundedSelfPayCents += selfPayRefund
        val refundedTotal = p.refundedInsuranceCents + p.refundedSelfPayCents
        p.refundStatus = when {
            refundedTotal <= 0 -> RefundStatus.NONE
            refundedTotal >= p.totalCents -> RefundStatus.FULL
            else -> RefundStatus.PARTIAL
        }
        store.set("payment:$orderId", p)
        return Triple(p, insuranceRefund, selfPayRefund)
    }
}
