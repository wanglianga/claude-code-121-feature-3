package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.InventoryLock
import com.pharmacy.coldchain.domain.InventoryLockStatus
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import org.springframework.stereotype.Service

/**
 * 库存锁定：仓库拣货即锁定（未出库前不实际扣减）。
 * 处方退回联动时，若已拣货未出库，锁定同步回滚（库存归还）；
 * 患者补充材料、药师重新审核通过后重新锁定；出库后转为已释放（实际扣减）。
 */
@Service
class InventoryService(private val store: JsonStore) {

    private fun key(orderId: String) = "lock:$orderId"

    fun get(orderId: String): InventoryLock? = store.get(key(orderId))

    fun require(orderId: String): InventoryLock =
        get(orderId) ?: throw NotFoundException("订单 $orderId 不存在库存锁定记录")

    /** 拣货锁定（仅在未出库前可锁定） */
    fun lock(order: ColdChainOrder, batchNo: String, operator: String): InventoryLock {
        get(order.id)?.let { existing ->
            if (existing.status == InventoryLockStatus.LOCKED || existing.status == InventoryLockStatus.RELOCKED) {
                throw ConflictException("订单 ${order.id} 药品已拣货锁定（${existing.status}），不能重复拣货")
            }
        }
        val l = InventoryLock(
            orderId = order.id,
            drugName = order.drugName,
            batchNo = batchNo,
            quantity = order.quantity,
            status = InventoryLockStatus.LOCKED,
            lockedAt = System.currentTimeMillis(),
            lockedBy = operator
        )
        store.set(key(order.id), l)
        return l
    }

    /** 处方退回联动：回滚库存锁定，库存归还可售 */
    fun rollback(orderId: String, reason: String): InventoryLock {
        val l = require(orderId)
        if (l.status != InventoryLockStatus.LOCKED && l.status != InventoryLockStatus.RELOCKED) {
            throw ConflictException("订单 $orderId 库存锁定状态为 ${l.status}，不能回滚")
        }
        l.status = InventoryLockStatus.ROLLED_BACK
        l.rolledBackAt = System.currentTimeMillis()
        l.rollBackReason = reason
        store.set(key(orderId), l)
        return l
    }

    /** 重新审核通过：重新锁定（锁定时间更新为重锁时刻） */
    fun relock(orderId: String): InventoryLock {
        val l = require(orderId)
        if (l.status != InventoryLockStatus.ROLLED_BACK) {
            throw ConflictException("订单 $orderId 库存锁定未处于回滚状态（${l.status}），不能重新锁定")
        }
        l.status = InventoryLockStatus.RELOCKED
        l.relockedAt = System.currentTimeMillis()
        l.rollBackReason = null
        store.set(key(orderId), l)
        return l
    }

    /** 已出库：锁定转实际扣减并释放 */
    fun release(orderId: String): InventoryLock? {
        val l = get(orderId) ?: return null
        if (l.status == InventoryLockStatus.LOCKED || l.status == InventoryLockStatus.RELOCKED) {
            l.status = InventoryLockStatus.RELEASED
            l.releasedAt = System.currentTimeMillis()
            store.set(key(orderId), l)
        }
        return l
    }
}
