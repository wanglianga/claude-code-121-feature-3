package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.CreateOrderRequest
import com.pharmacy.coldchain.domain.FollowUpRequest
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.OutboundRequest
import com.pharmacy.coldchain.domain.PhotoRequest
import com.pharmacy.coldchain.domain.PickStockRequest
import com.pharmacy.coldchain.domain.PrescriptionReturnRequest
import com.pharmacy.coldchain.domain.RejudgmentRequest
import com.pharmacy.coldchain.domain.ResolutionRequest
import com.pharmacy.coldchain.domain.ReviewRequest
import com.pharmacy.coldchain.domain.ReReviewRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.FollowUpService
import com.pharmacy.coldchain.service.OrderService
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
    private val followUps: FollowUpService
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

    /** 仓库拣货（出库前锁定库存、医保预结算） */
    @PostMapping("/{id}/pick-stock")
    fun pickStock(@PathVariable id: String, @RequestBody req: PickStockRequest, request: HttpServletRequest) =
        Auth.require(request, Role.WAREHOUSE).let { (uid, _) -> orders.pickStock(id, req, uid) }

    // ---------- 处方退回联动（剂量/适应症不符，暂停出库；已拣货资源同步回滚） ----------

    /** 药师退回处方：暂停出库并通知患者补充材料；已拣货的库存锁定/医保结算同步回滚 */
    @PostMapping("/{id}/prescription-return")
    fun prescriptionReturn(@PathVariable id: String, @RequestBody req: PrescriptionReturnRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> orders.prescriptionReturn(id, req, uid) }

    /** 药师对补充材料重新审核：通过则库存重新锁定、医保重新预结算，等待再次确认 */
    @PostMapping("/{id}/re-review")
    fun reReview(@PathVariable id: String, @RequestBody req: ReReviewRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> orders.reReview(id, req, uid) }

    /** 恢复出库前药师再次确认 */
    @PostMapping("/{id}/resume-confirm")
    fun resumeConfirm(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> orders.confirmResume(id, uid) }

    /** 处方退回联动记录 */
    @GetMapping("/{id}/prescription-returns")
    fun prescriptionReturns(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.INSURANCE)
        return orders.returns(id)
    }

    /** 药师留痕（退回/重审/恢复确认全过程） */
    @GetMapping("/{id}/pharmacist-audit")
    fun pharmacistAudit(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.PHARMACIST, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.ADMIN)
        return orders.auditList(id)
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
