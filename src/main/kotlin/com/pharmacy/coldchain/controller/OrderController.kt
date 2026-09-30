package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.CreateOrderRequest
import com.pharmacy.coldchain.domain.FollowUpRequest
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.OutboundRequest
import com.pharmacy.coldchain.domain.PhotoRequest
import com.pharmacy.coldchain.domain.PickRequest
import com.pharmacy.coldchain.domain.PrescriptionReturnRequest
import com.pharmacy.coldchain.domain.RejudgmentRequest
import com.pharmacy.coldchain.domain.ReReviewRequest
import com.pharmacy.coldchain.domain.ResolutionRequest
import com.pharmacy.coldchain.domain.ResumeConfirmRequest
import com.pharmacy.coldchain.domain.ReviewRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.FollowUpService
import com.pharmacy.coldchain.service.OrderService
import com.pharmacy.coldchain.service.PrescriptionReturnService
import com.pharmacy.coldchain.service.ResolutionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 药店/药房侧订单接口 */
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orders: OrderService,
    private val resolutions: ResolutionService,
    private val followUps: FollowUpService,
    private val returns: PrescriptionReturnService
) {

    @PostMapping
    fun create(@RequestBody req: CreateOrderRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST, Role.CUSTOMER_SERVICE).let { (uid, _) -> orders.create(req, uid) }

    @GetMapping
    fun list(
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) patientId: String?,
        request: HttpServletRequest
    ): Any {
        Auth.require(request, Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.INSURANCE)
        return orders.list(status, patientId)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.INSURANCE)
        return orders.pharmacyView(id)
    }

    /** 药店核心视图：药品是否仍可使用 */
    @GetMapping("/{id}/usability")
    fun usability(@PathVariable id: String, request: HttpServletRequest): Map<String, Any?> {
        Auth.require(request, Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.COMPENSATION)
        val v = orders.pharmacyView(id)
        return mapOf(
            "orderId" to id,
            "status" to v.order.status,
            "usability" to v.order.usability,
            "drugUsable" to v.drugUsable,
            "needsRejudgment" to v.needsRejudgment
        )
    }

    // ---------- 处方照片归档 ----------

    @PostMapping("/{id}/prescription-photos")
    fun addPhoto(@PathVariable id: String, @RequestBody req: PhotoRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST, Role.CUSTOMER_SERVICE).let { (uid, _) -> orders.addPhoto(id, req, uid) }

    @GetMapping("/{id}/prescription-photos")
    fun photos(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.PHARMACIST, Role.CUSTOMER_SERVICE, Role.COMPENSATION)
        return orders.photos(id)
    }

    // ---------- 审核流转 ----------

    @PostMapping("/{id}/prescription-review")
    fun prescriptionReview(@PathVariable id: String, @RequestBody req: ReviewRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> orders.prescriptionReview(id, req, uid) }

    @PostMapping("/{id}/pharmacist-review")
    fun pharmacistReview(@PathVariable id: String, @RequestBody req: ReviewRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> orders.pharmacistReview(id, req, uid) }

    @PostMapping("/{id}/outbound")
    fun outbound(@PathVariable id: String, @RequestBody req: OutboundRequest, request: HttpServletRequest) =
        Auth.require(request, Role.WAREHOUSE).let { (uid, _) -> orders.outbound(id, req, uid) }

    /** 仓库拣货：锁定库存 + 医保预结算，进入已拣货待出库 */
    @PostMapping("/{id}/pick")
    fun pick(@PathVariable id: String, @RequestBody req: PickRequest, request: HttpServletRequest) =
        Auth.require(request, Role.WAREHOUSE).let { (uid, _) -> orders.pick(id, req, uid) }

    // ---------- 处方退回联动（剂量/适应症不符） ----------

    /** 药师发起退回：暂停出库；已拣货未出库时库存锁定/医保结算/患者通知同步回滚 */
    @PostMapping("/{id}/prescription-return")
    fun prescriptionReturn(@PathVariable id: String, @RequestBody req: PrescriptionReturnRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> returns.initiate(id, req, uid) }

    /** 药师对患者补充材料重新审核（通过后库存重锁、医保重新预结算，等待最终确认） */
    @PostMapping("/{id}/re-review")
    fun reReview(@PathVariable id: String, @RequestBody req: ReReviewRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> returns.reReview(id, req, uid) }

    /** 恢复出库前药师再次确认 */
    @PostMapping("/{id}/resume-confirmation")
    fun resumeConfirmation(@PathVariable id: String, @RequestBody req: ResumeConfirmRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> returns.confirmResume(id, req, uid) }

    /** 处方退回联动 case 详情（含库存/医保/通知回滚与药师留痕） */
    @GetMapping("/{id}/prescription-return")
    fun prescriptionReturnDetail(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.INSURANCE, Role.COMPENSATION)
        val cases = returns.byOrder(id)
        return mapOf("orderId" to id, "cases" to cases, "active" to returns.activeCase(id))
    }

    /** 药师重新判断药品是否仍可使用 */
    @PostMapping("/{id}/rejudgment")
    fun rejudgment(@PathVariable id: String, @RequestBody req: RejudgmentRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> orders.rejudgment(id, req, uid) }

    // ---------- 赔付处理 ----------

    @GetMapping("/{id}/resolution/suggestion")
    fun suggestion(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.COMPENSATION, Role.CUSTOMER_SERVICE, Role.PHARMACIST)
        return resolutions.suggest(id)
    }

    @PostMapping("/{id}/resolution")
    fun resolve(@PathVariable id: String, @RequestBody req: ResolutionRequest, request: HttpServletRequest) =
        Auth.require(request, Role.COMPENSATION).let { (uid, _) -> resolutions.resolve(id, req, uid) }

    @GetMapping("/{id}/resolution")
    fun getResolution(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.COMPENSATION, Role.CUSTOMER_SERVICE, Role.PHARMACIST, Role.INSURANCE)
        return resolutions.get(id) ?: mapOf("orderId" to id, "resolution" to null)
    }

    // ---------- 用药回访 ----------

    @PostMapping("/{id}/follow-ups")
    fun addFollowUp(@PathVariable id: String, @RequestBody req: FollowUpRequest, request: HttpServletRequest) =
        Auth.require(request, Role.CUSTOMER_SERVICE, Role.PHARMACIST).let { (uid, _) -> followUps.add(id, req, uid) }

    @GetMapping("/{id}/follow-ups")
    fun followUps(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.CUSTOMER_SERVICE, Role.PHARMACIST)
        return followUps.list(id)
    }
}
