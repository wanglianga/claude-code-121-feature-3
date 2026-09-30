package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.StockItem
import com.pharmacy.coldchain.domain.StockLock
import com.pharmacy.coldchain.domain.StockLockStatus
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import org.springframework.stereotype.Service

/**
 * 库存服务：拣货锁定库存；处方退回时回滚释放；补充重审后按新记录重新锁定（锁定时间更新）；
 * 正式出库时把锁定转为扣减。
 *
 * Redis key：
 *   stock:{drugName}:{batchNo}      库存商品可用数量
 *   idx:stock                       库存商品索引
 *   lock:{lockId}                   锁定记录
 *   idx:lock:order:{orderId}        订单锁定记录列表（保留历次锁定/释放留痕）
 */
@Service
class StockService(private val store: JsonStore) {

    private fun itemKey(drugName: String, batchNo: String) = "stock:$drugName:$batchNo"
    private fun lockKey(id: String) = "lock:$id"
    private fun orderLocksKey(orderId: String) = "idx:lock:order:$orderId"

    // ---------- 库存账本 ----------

    fun restock(drugName: String, batchNo: String, qty: Int, operator: String): StockItem {
        if (qty <= 0) throw BadRequestException("入库数量必须大于 0")
        val key = itemKey(drugName, batchNo)
        val item = store.get<StockItem>(key) ?: StockItem(drugName, batchNo).also {
            store.sadd("idx:stock", key)
        }
        item.availableQty += qty
        store.set(key, item)
        return item
    }

    fun allStock(): List<StockItem> =
        store.smembers("idx:stock").mapNotNull { store.get<StockItem>(it) }.sortedWith(
            compareBy({ it.drugName }, { it.batchNo })
        )

    // ---------- 锁定 / 释放 / 重锁 / 扣减 ----------

    /** 拣货锁定：校验批号库存，占用可用量，生成锁定记录 */
    fun lock(order: ColdChainOrder, batchNo: String, operator: String): StockLock {
        if (batchNo.isBlank()) throw BadRequestException("拣货批号不能为空")
        if (activeLock(order.id) != null) {
            throw ConflictException("订单 ${order.id} 已有进行中的库存锁定，不能重复拣货")
        }
        val key = itemKey(order.drugName, batchNo)
        val item = store.get<StockItem>(key)
            ?: throw ConflictException("药品 ${order.drugName} 批号 $batchNo 无库存记录，无法拣货锁定")
        if (item.availableQty < order.quantity) {
            throw ConflictException(
                "药品 ${order.drugName} 批号 $batchNo 可用库存 ${item.availableQty} 不足，需要 ${order.quantity}"
            )
        }
        item.availableQty -= order.quantity
        store.set(key, item)

        val lockNo = lastLock(order.id)?.let { it.lockNo + 1 } ?: 1
        val lock = StockLock(
            id = store.nextId("L"),
            orderId = order.id,
            drugName = order.drugName,
            batchNo = batchNo,
            qty = order.quantity,
            status = StockLockStatus.LOCKED,
            lockedAt = System.currentTimeMillis(),
            lockNo = lockNo,
            operator = operator
        )
        persistLock(lock)
        return lock
    }

    /** 退回回滚：释放当前锁定，把数量加回可用库存 */
    fun releaseForReturn(orderId: String, reason: String): StockLock? {
        val lock = activeLock(orderId) ?: return null
        val item = store.get<StockItem>(itemKey(lock.drugName, lock.batchNo))
            ?: StockItem(lock.drugName, lock.batchNo).also { store.sadd("idx:stock", itemKey(lock.drugName, lock.batchNo)) }
        item.availableQty += lock.qty
        store.set(itemKey(lock.drugName, lock.batchNo), item)

        lock.status = StockLockStatus.RELEASED
        lock.releasedAt = System.currentTimeMillis()
        lock.releaseReason = reason
        persistLock(lock)
        return lock
    }

    /** 出库扣减：锁定量在拣货时已占用，这里仅把锁定状态置为扣减 */
    fun deduct(lockId: String): StockLock {
        val lock = findLock(lockId) ?: throw NotFoundException("库存锁定 $lockId 不存在")
        if (lock.status != StockLockStatus.LOCKED) {
            throw ConflictException("库存锁定 $lockId 当前状态为 ${lock.status}，不能出库扣减")
        }
        lock.status = StockLockStatus.DEDUCTED
        persistLock(lock)
        return lock
    }

    /**
     * 未拣货直接出库（兼容无独立拣货环节的流程）：若该批号已纳管库存则校验并即时扣减；
     * 若无库存账本记录，视为非纳管库存，跳过库存扣减返回 null。
     */
    fun immediateDeduct(order: ColdChainOrder, batchNo: String, operator: String): StockLock? {
        val item = store.get<StockItem>(itemKey(order.drugName, batchNo)) ?: return null
        if (item.availableQty < order.quantity) {
            throw ConflictException(
                "药品 ${order.drugName} 批号 $batchNo 可用库存 ${item.availableQty} 不足，需要 ${order.quantity}"
            )
        }
        item.availableQty -= order.quantity
        store.set(itemKey(order.drugName, batchNo), item)
        val lock = StockLock(
            id = store.nextId("L"),
            orderId = order.id,
            drugName = order.drugName,
            batchNo = batchNo,
            qty = order.quantity,
            status = StockLockStatus.DEDUCTED,
            lockedAt = System.currentTimeMillis(),
            lockNo = 1,
            operator = operator
        )
        persistLock(lock)
        return lock
    }

    fun activeLock(orderId: String): StockLock? =
        locksOf(orderId).filter { it.status == StockLockStatus.LOCKED }.maxByOrNull { it.lockNo }

    fun locksOf(orderId: String): List<StockLock> =
        store.smembers(orderLocksKey(orderId)).mapNotNull { findLock(it) }.sortedWith(
            compareBy({ it.lockNo }, { it.lockedAt })
        )

    private fun lastLock(orderId: String): StockLock? = locksOf(orderId).maxByOrNull { it.lockNo }

    fun findLock(id: String): StockLock? = store.get(lockKey(id))

    private fun persistLock(lock: StockLock) {
        store.set(lockKey(lock.id), lock)
        store.sadd(orderLocksKey(lock.orderId), lock.id)
    }
}
