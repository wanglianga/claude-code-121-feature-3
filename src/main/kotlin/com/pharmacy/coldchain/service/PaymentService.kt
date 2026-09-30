package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.PaymentSplit
import com.pharmacy.coldchain.domain.RefundStatus
import com.pharmacy.coldchain.domain.SettlementStatus
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/** 医保/自费拆分与退款 */
@Service
class PaymentService(private val store: JsonStore) {

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

    /**
     * 拣货后医保预结算：医保/自费拆分在出库前先与医保系统确认。
     * 幂等：已处于预结算/重新结算状态时直接返回。
     */
    fun preSettle(orderId: String): PaymentSplit {
        val p = require(orderId)
        if (p.settlementStatus == SettlementStatus.PRE_SETTLED || p.settlementStatus == SettlementStatus.RESETTLED) {
            return p
        }
        if (p.settlementStatus != SettlementStatus.NONE && p.settlementStatus != SettlementStatus.ROLLED_BACK) {
            throw ConflictException("订单 $orderId 医保结算状态为 ${p.settlementStatus}，不能预结算")
        }
        val now = System.currentTimeMillis()
        val resettling = p.settlementStatus == SettlementStatus.ROLLED_BACK
        p.settlementStatus = if (resettling) SettlementStatus.RESETTLED else SettlementStatus.PRE_SETTLED
        if (resettling) {
            p.resettledAt = now
            p.settlementHistory.add("${now}: 处方补充后重新预结算通过（医保 ${p.insuranceCents} / 自费 ${p.selfPayCents} 分）")
        } else {
            p.preSettledAt = now
            p.settlementHistory.add("${now}: 拣货后医保预结算通过（医保 ${p.insuranceCents} / 自费 ${p.selfPayCents} 分）")
        }
        store.set("payment:$orderId", p)
        return p
    }

    /**
     * 处方退回联动：医保预结算同步回滚（未实际出库收费，仅撤销预授权）。
     */
    fun rollbackSettlement(orderId: String, reason: String): PaymentSplit {
        val p = require(orderId)
        if (p.settlementStatus != SettlementStatus.PRE_SETTLED && p.settlementStatus != SettlementStatus.RESETTLED) {
            throw ConflictException("订单 $orderId 医保结算状态为 ${p.settlementStatus}，无需回滚")
        }
        if (p.refundedInsuranceCents + p.refundedSelfPayCents > 0) {
            throw ConflictException("订单 $orderId 已发生实际退款，不能按预结算回滚处理")
        }
        val now = System.currentTimeMillis()
        p.settlementStatus = SettlementStatus.ROLLED_BACK
        p.settlementRolledBackAt = now
        p.settlementHistory.add("$now: 处方退回联动回滚医保预结算（$reason）")
        store.set("payment:$orderId", p)
        return p
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
