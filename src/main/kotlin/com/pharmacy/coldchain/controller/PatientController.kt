package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.ReturnRequestBody
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.SignRequest
import com.pharmacy.coldchain.domain.SupplementRequest
import com.pharmacy.coldchain.service.OrderService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 患者端接口 */
@RestController
class PatientController(private val orders: OrderService) {

    /** 患者签收：确认本人/代收人身份、药品外观、温度标签、用药提醒 */
    @PostMapping("/api/orders/{id}/sign")
    fun sign(@PathVariable id: String, @RequestBody req: SignRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT).let { (uid, _) -> orders.sign(id, req, uid) }

    /** 患者申请退药 */
    @PostMapping("/api/orders/{id}/return-request")
    fun returnRequest(@PathVariable id: String, @RequestBody req: ReturnRequestBody, request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT).let { (uid, _) -> orders.returnRequest(id, req, uid) }

    /** 处方退回联动：患者补充材料（可附处方/证明照片），等待药师重新审核 */
    @PostMapping("/api/orders/{id}/supplement")
    fun supplement(@PathVariable id: String, @RequestBody req: SupplementRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT).let { (uid, _) -> orders.supplement(id, req, uid) }

    /** 患者端视图：超温、补发、退款、用药提醒、处方退回联动与恢复条件的当前状态 */
    @GetMapping("/api/patient/orders/{id}")
    fun patientView(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT).let { (uid, _) -> orders.patientView(id, uid) }

    /** 患者通知（处方退回、回滚、恢复出库等联动提示） */
    @GetMapping("/api/patient/notifications")
    fun notifications(request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT).let { (uid, _) -> orders.patientNotifications(uid) }
}
