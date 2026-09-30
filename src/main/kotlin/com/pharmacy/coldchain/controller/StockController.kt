package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.RestockRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.StockService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 库存：入库、库存账本、订单库存锁定记录 */
@RestController
@RequestMapping("/api")
class StockController(private val stocks: StockService) {

    /** 入库（为拣货准备可用库存） */
    @PostMapping("/stock/restock")
    fun restock(@RequestBody req: RestockRequest, httpRequest: HttpServletRequest) =
        Auth.require(httpRequest, Role.WAREHOUSE, Role.ADMIN).let { (uid, _) ->
            stocks.restock(req.drugName, req.batchNo, req.qty, uid)
        }

    /** 库存账本 */
    @GetMapping("/stock")
    fun all(httpRequest: HttpServletRequest): Any {
        Auth.require(httpRequest, Role.WAREHOUSE, Role.PHARMACIST, Role.COMPENSATION, Role.ADMIN)
        return stocks.allStock()
    }

    /** 某订单的库存锁定记录（含退回释放、重锁留痕） */
    @GetMapping("/orders/{id}/stock-locks")
    fun locksOfOrder(@PathVariable id: String, httpRequest: HttpServletRequest): Any {
        Auth.require(httpRequest, Role.WAREHOUSE, Role.PHARMACIST, Role.COMPENSATION, Role.INSURANCE)
        return mapOf("orderId" to id, "locks" to stocks.locksOf(id))
    }
}
