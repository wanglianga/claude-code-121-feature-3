package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.domain.NotificationType
import com.pharmacy.coldchain.domain.PatientNotification
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/**
 * 患者通知：处方退回联动各环节同步告知患者，患者端可查看退回原因、
 * 需要补充的材料、回滚情况与恢复出库条件。
 * Redis list: notify:{patientId}；索引：idx:notify:order:{orderId}
 */
@Service
class NotificationService(private val store: JsonStore) {

    fun send(
        patientId: String,
        orderId: String,
        type: NotificationType,
        title: String,
        content: String,
        resumeConditions: List<String> = emptyList()
    ): PatientNotification {
        val n = PatientNotification(
            id = store.nextId("N"),
            patientId = patientId,
            orderId = orderId,
            type = type,
            title = title,
            content = content,
            resumeConditions = resumeConditions,
            createdAt = System.currentTimeMillis()
        )
        store.rpushJson("notify:$patientId", n)
        store.sadd("idx:notify:order:$orderId", n.id)
        return n
    }

    fun forPatient(patientId: String): List<PatientNotification> =
        store.lrangeJson("notify:$patientId")

    fun forOrder(patientId: String, orderId: String): List<PatientNotification> =
        forPatient(patientId).filter { it.orderId == orderId }
}
