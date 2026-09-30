package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.domain.NotificationStatus
import com.pharmacy.coldchain.domain.NotificationType
import com.pharmacy.coldchain.domain.PatientNotification
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import org.springframework.stereotype.Service

/**
 * 患者通知服务：处方退回/恢复出库时与库存、医保同步联动。
 * 退回时把此前"已拣货即将出库"的通知撤回，并下发补充材料通知；
 * 重审通过与最终确认时分别下发恢复进度通知。
 */
@Service
class NotificationService(private val store: JsonStore) {

    fun send(
        orderId: String,
        patientId: String,
        type: NotificationType,
        title: String,
        content: String,
        sentBy: String
    ): PatientNotification {
        val n = PatientNotification(
            id = store.nextId("N"),
            orderId = orderId,
            patientId = patientId,
            type = type,
            title = title,
            content = content,
            sentBy = sentBy,
            sentAt = System.currentTimeMillis()
        )
        store.set("notif:${n.id}", n)
        store.sadd("idx:notif:order:$orderId", n.id)
        store.sadd("idx:notif:patient:$patientId", n.id)
        return n
    }

    /** 撤回订单下指定类型且仍生效的通知（如退回时撤回"已拣货即将出库"） */
    fun recallByType(orderId: String, type: NotificationType): List<PatientNotification> =
        byOrder(orderId).filter { it.type == type && it.status == NotificationStatus.SENT }
            .onEach {
                it.status = NotificationStatus.RECALLED
                it.recalledAt = System.currentTimeMillis()
                store.set("notif:${it.id}", it)
            }

    fun byOrder(orderId: String): List<PatientNotification> =
        store.smembers("idx:notif:order:$orderId")
            .mapNotNull { store.get<PatientNotification>("notif:$it") }
            .sortedBy { it.sentAt }

    fun byPatient(patientId: String): List<PatientNotification> =
        store.smembers("idx:notif:patient:$patientId")
            .mapNotNull { store.get<PatientNotification>("notif:$it") }
            .sortedByDescending { it.sentAt }
}
