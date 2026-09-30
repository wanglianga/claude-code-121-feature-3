package com.pharmacy.coldchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * 端到端流程测试：使用真实 Redis（database 1），每个用例前清空。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.data.redis.database=1"]
)
class ColdChainFlowTests {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var om: ObjectMapper

    @BeforeEach
    fun clean() {
        redis.execute(RedisCallback<Void> { conn -> conn.serverCommands().flushDb(); null })
    }

    // ---------- 测试工具 ----------

    private fun headers(role: String, user: String) = HttpHeaders().apply {
        set("X-Role", role)
        set("X-User-Id", user)
        contentType = MediaType.APPLICATION_JSON
    }

    private fun post(url: String, body: Any?, role: String, user: String): ResponseEntity<JsonNode> =
        rest.exchange(url, HttpMethod.POST, HttpEntity(body?.let { om.writeValueAsString(it) }, headers(role, user)), JsonNode::class.java)

    private fun get(url: String, role: String, user: String): ResponseEntity<JsonNode> =
        rest.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers(role, user)), JsonNode::class.java)

    private fun createOrder(patientId: String = "patient-1", total: Long = 100000, insurance: Long = 70000): String {
        val resp = post("/api/orders", mapOf(
            "patientId" to patientId, "patientName" to "张三",
            "drugType" to "INSULIN", "drugName" to "门冬胰岛素注射液", "quantity" to 2,
            "prescriptionNo" to "RX-2026-0001",
            "totalAmountCents" to total, "insuranceAmountCents" to insurance
        ), "PHARMACIST", "pharmacist-1")
        assertThat(resp.statusCode.value()).isEqualTo(200)
        return resp.body!!.get("id").asText()
    }

    private fun trainRider(riderId: String = "rider-1") {
        val resp = post("/api/riders/$riderId/trainings",
            mapOf("course" to "COLD_CHAIN_BASICS", "score" to 95, "passed" to true), "ADMIN", "admin-1")
        assertThat(resp.statusCode.value()).isEqualTo(200)
    }

    /** 推进到配送中：照片归档 → 处方审核 → 药师复核 → 出库 → 骑手取药 */
    private fun toDelivering(orderId: String, boxNo: String = "BOX-001", riderId: String = "rider-1") {
        assertThat(post("/api/orders/$orderId/prescription-photos",
            mapOf("url" to "https://img.example.com/rx/$orderId.jpg"), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/prescription-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/pharmacist-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-2").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B20260901", "expiryDate" to "2027-06-30", "boxNo" to boxNo,
            "icePackCount" to 2, "outboundTempC" to 5.0, "etaMinutes" to 45
        ), "WAREHOUSE", "warehouse-1").statusCode.value()).isEqualTo(200)
        trainRider(riderId)
        assertThat(post("/api/orders/$orderId/pickup", null, "RIDER", riderId).statusCode.value()).isEqualTo(200)
    }

    private fun signOk(orderId: String, patientId: String = "patient-1"): ResponseEntity<JsonNode> =
        post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to true, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", patientId)

    /** 推进到待出库：照片归档 → 处方审核 → 药师复核 */
    private fun toReady(orderId: String) {
        assertThat(post("/api/orders/$orderId/prescription-photos",
            mapOf("url" to "https://img.example.com/rx/$orderId.jpg"), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/prescription-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/pharmacist-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-2").statusCode.value()).isEqualTo(200)
    }

    private fun restock(drugName: String = "门冬胰岛素注射液", batchNo: String = "B20260901", qty: Int = 5) {
        val resp = post("/api/stock/restock",
            mapOf("drugName" to drugName, "batchNo" to batchNo, "qty" to qty), "WAREHOUSE", "warehouse-1")
        assertThat(resp.statusCode.value()).isEqualTo(200)
    }

    private fun outboundBody(orderId: String, batchNo: String = "B20260901", boxNo: String = "BOX-001") =
        mapOf(
            "batchNo" to batchNo, "expiryDate" to "2027-06-30", "boxNo" to boxNo,
            "icePackCount" to 2, "outboundTempC" to 5.0, "etaMinutes" to 45
        )

    // ---------- 用例 ----------

    @Test
    fun `正常流程：下单到签收回访与保温箱回收`() {
        val orderId = createOrder()

        // 未归档处方照片时不能审核
        val earlyReview = post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        assertThat(earlyReview.statusCode.value()).isEqualTo(409)

        toDelivering(orderId)

        // 出库信息完整
        val view = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!
        val order = view.get("order")
        assertThat(order.get("status").asText()).isEqualTo("DELIVERING")
        assertThat(order.get("batchNo").asText()).isEqualTo("B20260901")
        assertThat(order.get("boxNo").asText()).isEqualTo("BOX-001")
        assertThat(order.get("icePackCount").asInt()).isEqualTo(2)
        assertThat(order.get("outboundTempC").asDouble()).isEqualTo(5.0)
        assertThat(order.get("estimatedDeliveryAt").asLong()).isGreaterThan(order.get("outboundAt").asLong())
        assertThat(view.get("drugUsable").asBoolean()).isTrue()

        // 途中正常遥测
        val t = post("/api/orders/$orderId/telemetry",
            mapOf("tempC" to 4.5, "lat" to 31.23, "lng" to 121.47, "deliveryStatus" to "ON_ROUTE"), "RIDER", "rider-1")
        assertThat(t.statusCode.value()).isEqualTo(200)

        // 代收人签收
        val signed = post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "PROXY", "receiverName" to "李四", "receiverRelation" to "配偶", "idLast4" to "5678",
            "appearanceOk" to true, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        assertThat(signed.statusCode.value()).isEqualTo(200)
        assertThat(signed.body!!.get("status").asText()).isEqualTo("SIGNED")
        assertThat(signed.body!!.get("signOff").get("receiverType").asText()).isEqualTo("PROXY")

        // 用药回访
        val fu = post("/api/orders/$orderId/follow-ups",
            mapOf("result" to "OK", "note" to "患者已按提醒冷藏保存"), "CUSTOMER_SERVICE", "cs-1")
        assertThat(fu.statusCode.value()).isEqualTo(200)

        // 保温箱回收循环
        val recycled = post("/api/boxes/BOX-001/recycle", mapOf("conditionOk" to true), "WAREHOUSE", "warehouse-1")
        assertThat(recycled.statusCode.value()).isEqualTo(200)
        assertThat(recycled.body!!.get("status").asText()).isEqualTo("READY")
        assertThat(recycled.body!!.get("timesUsed").asInt()).isEqualTo(1)

        // 患者端视图
        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("status").asText()).isEqualTo("SIGNED")
        assertThat(pv.get("overTemp").get("occurred").asBoolean()).isFalse()
        assertThat(pv.get("medicationReminders").size()).isGreaterThan(0)
    }

    @Test
    fun `处方审核退回产生事件且阻断流程，补充后可重审`() {
        val orderId = createOrder()
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/1.jpg"), "PHARMACIST", "pharmacist-1")

        val rejected = post("/api/orders/$orderId/prescription-review",
            mapOf("approved" to false, "comment" to "处方照片模糊"), "PHARMACIST", "pharmacist-1")
        assertThat(rejected.statusCode.value()).isEqualTo(200)
        assertThat(rejected.body!!.get("status").asText()).isEqualTo("PRESCRIPTION_REJECTED")

        // 事件已产生
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "PRESCRIPTION_REJECTED" }).isTrue()

        // 退回状态不能出库
        val outbound = post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-9",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")
        assertThat(outbound.statusCode.value()).isEqualTo(409)

        // 补充照片后重新审核通过
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/2.jpg"), "PHARMACIST", "pharmacist-1")
        val reReview = post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        assertThat(reReview.body!!.get("status").asText()).isEqualTo("PHARMACIST_REVIEWING")
    }

    @Test
    fun `骑手绕路超温：药师判不可用，赔付补发退款并记骑手责任`() {
        val orderId = createOrder()
        toDelivering(orderId)

        // 绕路 + 超温遥测
        val t = post("/api/orders/$orderId/telemetry",
            mapOf("tempC" to 12.5, "lat" to 31.3, "lng" to 121.5, "detour" to true), "RIDER", "rider-1")
        assertThat(t.statusCode.value()).isEqualTo(200)

        // 订单进入异常，待药师重判
        val usability = get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
        assertThat(usability.get("status").asText()).isEqualTo("DELIVERY_EXCEPTION")
        assertThat(usability.get("needsRejudgment").asBoolean()).isTrue()
        assertThat(usability.get("drugUsable").isNull).isTrue()

        // 异常事件类型为骑手绕路超温
        val incidents = get("/api/orders/$orderId/incidents", "COMPENSATION", "comp-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "RIDER_DETOUR_OVERTEMP" }).isTrue()

        // 超温状态下患者不能签收
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(409)

        // 药师判定不可继续使用
        val rej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to false, "comment" to "胰岛素长时间超温，效价无法保证"), "PHARMACIST", "pharmacist-1")
        assertThat(rej.body!!.get("status").asText()).isEqualTo("AWAITING_RESOLUTION")

        // 赔付建议：全额退款 + 补发 + 骑手全责
        val suggestion = get("/api/orders/$orderId/resolution/suggestion", "COMPENSATION", "comp-1").body!!
        assertThat(suggestion.get("suggestedReissue").asBoolean()).isTrue()
        assertThat(suggestion.get("suggestedRefundCents").asLong()).isEqualTo(100000)
        assertThat(suggestion.get("suggestedRiderLiability").asText()).isEqualTo("FULL")

        // 赔付处理：报损 + 补发 + 全额退款 + 骑手全责 + 风险提醒
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "SCRAPPED", "reissue" to true, "refundAmountCents" to 100000,
            "riderLiability" to "FULL", "riderFineCents" to 50000,
            "riskReminder" to "本单药品曾因超温报损，请勿使用原配送药品"
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)
        val r = resolution.body!!
        assertThat(r.get("insuranceRefundCents").asLong()).isEqualTo(70000)
        assertThat(r.get("selfPayRefundCents").asLong()).isEqualTo(30000)
        val newOrderId = r.get("reissueOrderId").asText()
        assertThat(newOrderId).isNotBlank()

        // 原订单已补发结案，事件全部关闭
        val oldOrder = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(oldOrder.get("status").asText()).isEqualTo("REISSUED")
        val incidentsAfter = get("/api/orders/$orderId/incidents", "COMPENSATION", "comp-1").body!!
        assertThat(incidentsAfter.all { it.get("status").asText() == "RESOLVED" }).isTrue()

        // 补发订单直接进入待出库，且不再收费
        val newOrder = get("/api/orders/$newOrderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(newOrder.get("status").asText()).isEqualTo("READY_FOR_OUTBOUND")
        assertThat(newOrder.get("totalAmountCents").asLong()).isEqualTo(0)
        assertThat(newOrder.get("reissueOfOrderId").asText()).isEqualTo(orderId)

        // 患者端：超温、补发、退款、风险提醒可见
        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("overTemp").get("occurred").asBoolean()).isTrue()
        assertThat(pv.get("overTemp").get("maxTempC").asDouble()).isEqualTo(12.5)
        assertThat(pv.get("reissue").get("newOrderId").asText()).isEqualTo(newOrderId)
        assertThat(pv.get("refund").get("status").asText()).isEqualTo("FULL")
        assertThat(pv.get("refund").get("refundedSelfPayCents").asLong()).isEqualTo(30000)
        assertThat(pv.get("riskReminders").any { it.asText().contains("超温") }).isTrue()
    }

    @Test
    fun `患者不在家：签收失败后药师判可用，恢复配送并成功签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        val attempt = post("/api/orders/$orderId/delivery-attempt",
            mapOf("result" to "PATIENT_NOT_HOME"), "RIDER", "rider-1")
        assertThat(attempt.body!!.get("status").asText()).isEqualTo("SIGN_FAILED")

        // 签收失败必须药师重新判断后才能继续
        val usability = get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
        assertThat(usability.get("needsRejudgment").asBoolean()).isTrue()

        val rej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to true, "comment" to "在途时间短，温度记录正常，可继续配送"), "PHARMACIST", "pharmacist-1")
        assertThat(rej.body!!.get("status").asText()).isEqualTo("DELIVERING")

        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `保温箱开盖异常：第二次开盖上报事件并冻结签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        post("/api/orders/$orderId/telemetry", mapOf("tempC" to 5.0, "boxOpened" to true), "RIDER", "rider-1")
        // 第一次开盖不异常
        assertThat(get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
            .get("needsRejudgment").asBoolean()).isFalse()

        post("/api/orders/$orderId/telemetry", mapOf("tempC" to 5.0, "boxOpened" to true), "RIDER", "rider-1")
        val u = get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
        assertThat(u.get("needsRejudgment").asBoolean()).isTrue()
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "BOX_OPENED_ABNORMAL" }).isTrue()
    }

    @Test
    fun `签收时外包装破损：签收失败进入药师重判，判可用后退回库存结案`() {
        val orderId = createOrder()
        toDelivering(orderId)

        val damaged = post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to false, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        assertThat(damaged.statusCode.value()).isEqualTo(409)

        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "PACKAGE_DAMAGED" }).isTrue()

        // 药师判定药品本身仍可使用 → 恢复配送
        val rej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to true, "comment" to "仅外包装破损，药品完好"), "PHARMACIST", "pharmacist-1")
        assertThat(rej.body!!.get("status").asText()).isEqualTo("DELIVERING")

        // 患者仍要求退药 → 药师再次判断 → 进入待赔付处理
        post("/api/orders/$orderId/return-request", mapOf("reason" to "外包装破损，不敢使用"), "PATIENT", "patient-1")
        post("/api/orders/$orderId/rejudgment", mapOf("usable" to true, "comment" to "药品完好，可退回库存"), "PHARMACIST", "pharmacist-1")

        // 按退回库存 + 全额退款 + 补发结案
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "RETURNED_TO_STOCK", "reissue" to true, "refundAmountCents" to 100000
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)

        // 药师判可用却按报损处理会被拒绝（另起一单验证）
        val orderId2 = createOrder()
        toDelivering(orderId2, boxNo = "BOX-002")
        post("/api/orders/$orderId2/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to false, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        post("/api/orders/$orderId2/rejudgment", mapOf("usable" to true, "comment" to "药品完好"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId2/return-request", mapOf("reason" to "拒收"), "PATIENT", "patient-1")
        post("/api/orders/$orderId2/rejudgment", mapOf("usable" to true, "comment" to "药品完好，可退回库存"), "PHARMACIST", "pharmacist-1")
        val bad = post("/api/orders/$orderId2/resolution",
            mapOf("drugDisposition" to "SCRAPPED", "refundAmountCents" to 100000), "COMPENSATION", "comp-1")
        assertThat(bad.statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `签收后患者申请退药：药师判不可用，退款结案`() {
        val orderId = createOrder()
        toDelivering(orderId)
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)

        val ret = post("/api/orders/$orderId/return-request", mapOf("reason" to "担心途中温度异常"), "PATIENT", "patient-1")
        assertThat(ret.body!!.get("status").asText()).isEqualTo("RETURN_REQUESTED")

        // 退药必须药师重新判断
        post("/api/orders/$orderId/rejudgment", mapOf("usable" to false, "comment" to "无法确认途中冷链完整"), "PHARMACIST", "pharmacist-1")
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "SCRAPPED", "reissue" to false, "refundAmountCents" to 100000,
            "riskReminder" to "如已使用本批药品，请密切观察并及时复诊"
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)

        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("status").asText()).isEqualTo("CLOSED")
        assertThat(pv.get("refund").get("status").asText()).isEqualTo("FULL")
        assertThat(pv.get("riskReminders").size()).isEqualTo(1)
    }

    @Test
    fun `温度标签异常导致签收失败，需药师重判`() {
        val orderId = createOrder()
        toDelivering(orderId)
        val resp = post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to true, "tempLabelOk" to false, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        assertThat(resp.statusCode.value()).isEqualTo(409)
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "OVER_TEMPERATURE" }).isTrue()
    }

    @Test
    fun `未培训骑手不能取药，培训通过后可以`() {
        val orderId = createOrder()
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/1.jpg"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/pharmacist-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-2")
        post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-003",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")

        val denied = post("/api/orders/$orderId/pickup", null, "RIDER", "rider-new")
        assertThat(denied.statusCode.value()).isEqualTo(409)

        trainRider("rider-new")
        val ok = post("/api/orders/$orderId/pickup", null, "RIDER", "rider-new")
        assertThat(ok.statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `权限与归属：角色校验和患者越权拦截`() {
        val orderId = createOrder()

        // 缺少请求头
        assertThat(rest.exchange("/api/orders/$orderId", HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), JsonNode::class.java)
            .statusCode.value()).isEqualTo(401)

        // 患者不能调用药师接口
        assertThat(post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PATIENT", "patient-1")
            .statusCode.value()).isEqualTo(403)

        // 其他患者不能查看/签收
        toDelivering(orderId)
        assertThat(get("/api/patient/orders/$orderId", "PATIENT", "patient-2").statusCode.value()).isEqualTo(403)
        assertThat(signOk(orderId, patientId = "patient-2").statusCode.value()).isEqualTo(403)

        // 非本单骑手不能上报遥测
        trainRider("rider-2")
        assertThat(post("/api/orders/$orderId/telemetry", mapOf("tempC" to 5.0), "RIDER", "rider-2")
            .statusCode.value()).isEqualTo(403)
    }

    @Test
    fun `医保自费拆分与部分退款按比例拆分`() {
        val orderId = createOrder(total = 100000, insurance = 70000)
        val split = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(split.get("insuranceCents").asLong()).isEqualTo(70000)
        assertThat(split.get("selfPayCents").asLong()).isEqualTo(30000)

        // 部分退款 500 元 → 医保 350 / 自费 150
        val refund = post("/api/orders/$orderId/refund", mapOf("amountCents" to 50000), "INSURANCE", "insurance-1")
        assertThat(refund.statusCode.value()).isEqualTo(200)
        assertThat(refund.body!!.get("insuranceRefundCents").asLong()).isEqualTo(35000)
        assertThat(refund.body!!.get("selfPayRefundCents").asLong()).isEqualTo(15000)
        assertThat(refund.body!!.get("payment").get("refundStatus").asText()).isEqualTo("PARTIAL")

        // 超额退款被拒绝
        assertThat(post("/api/orders/$orderId/refund", mapOf("amountCents" to 60000), "INSURANCE", "insurance-1")
            .statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `出库校验：超温禁止出库，占用中的保温箱不能再用`() {
        val orderId = createOrder()
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/1.jpg"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/pharmacist-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-2")

        // 出库温度超范围
        val hot = post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-004",
            "icePackCount" to 1, "outboundTempC" to 15.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")
        assertThat(hot.statusCode.value()).isEqualTo(400)

        // 正常出库后，同一保温箱不能再用于其他订单
        assertThat(post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-004",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1").statusCode.value()).isEqualTo(200)

        val orderId2 = createOrder()
        post("/api/orders/$orderId2/prescription-photos", mapOf("url" to "https://img.example.com/rx/2.jpg"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId2/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId2/pharmacist-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-2")
        val conflict = post("/api/orders/$orderId2/outbound", mapOf(
            "batchNo" to "B2", "expiryDate" to "2027-01-01", "boxNo" to "BOX-004",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")
        assertThat(conflict.statusCode.value()).isEqualTo(409)
    }

    // ---------- 处方退回联动（剂量/适应症不符） ----------

    @Test
    fun `已拣货未出库退回：库存医保通知三项回滚，补充重审后确认恢复并正常出库`() {
        val orderId = createOrder()
        toReady(orderId)
        restock()

        // 仓库拣货：库存锁定 + 医保预结算
        val picked = post("/api/orders/$orderId/pick", mapOf("batchNo" to "B20260901"), "WAREHOUSE", "warehouse-1")
        assertThat(picked.statusCode.value()).isEqualTo(200)
        assertThat(picked.body!!.get("status").asText()).isEqualTo("PICKED")
        val firstLockId = picked.body!!.get("stockLockId").asText()

        // 库存被占用 2，可用量 5→3；医保预结算
        var stock = get("/api/stock", "WAREHOUSE", "warehouse-1").body!!
        assertThat(stock.first { it.get("batchNo").asText() == "B20260901" }.get("availableQty").asInt()).isEqualTo(3)
        val settle0 = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(settle0.get("settlementStatus").asText()).isEqualTo("PRE_SETTLED")

        // 药师发现剂量不符，发起退回
        val ret = post("/api/orders/$orderId/prescription-return",
            mapOf("reason" to "DOSAGE_MISMATCH", "detail" to "单次剂量超说明书上限"), "PHARMACIST", "pharmacist-1")
        assertThat(ret.statusCode.value()).isEqualTo(200)
        val case = ret.body!!
        assertThat(case.get("status").asText()).isEqualTo("OPEN")
        assertThat(case.get("pickedWhenReturned").asBoolean()).isTrue()
        val caseId = case.get("id").asText()

        // 服务暂停出库，出库与取药均被拦截
        assertThat(get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!
            .get("order").get("status").asText()).isEqualTo("OUTBOUND_PAUSED")
        assertThat(post("/api/orders/$orderId/outbound", outboundBody(orderId), "WAREHOUSE", "warehouse-1")
            .statusCode.value()).isEqualTo(409)
        assertThat(post("/api/orders/$orderId/pickup", null, "RIDER", "rider-1").statusCode.value()).isEqualTo(409)

        // 三项回滚：库存锁定释放并回库（可用量恢复 5）；医保预结算回滚；拣货通知撤回
        stock = get("/api/stock", "WAREHOUSE", "warehouse-1").body!!
        assertThat(stock.first { it.get("batchNo").asText() == "B20260901" }.get("availableQty").asInt()).isEqualTo(5)
        val locks = get("/api/orders/$orderId/stock-locks", "WAREHOUSE", "warehouse-1").body!!.get("locks")
        assertThat(locks.first { it.get("id").asText() == firstLockId }.get("status").asText()).isEqualTo("RELEASED")
        val settle1 = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(settle1.get("settlementStatus").asText()).isEqualTo("ROLLED_BACK")
        val notifs = get("/api/patient/notifications", "PATIENT", "patient-1").body!!
        assertThat(notifs.any { it.get("type").asText() == "PICKED_READY" && it.get("status").asText() == "RECALLED" }).isTrue()
        // 患者同时收到补充材料通知
        assertThat(notifs.any { it.get("type").asText() == "PRESCRIPTION_RETURNED" }).isTrue()

        // 异常事件留痕
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "PRESCRIPTION_RETURNED" }).isTrue()

        // 患者端：暂停出库、需补充材料、可看到回滚后的医保状态
        val pv0 = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        val pr0 = pv0.get("prescriptionReturn")
        assertThat(pr0.get("outboundPaused").asBoolean()).isTrue()
        assertThat(pr0.get("needSupplement").asBoolean()).isTrue()
        assertThat(pr0.get("settlement").get("status").asText()).isEqualTo("ROLLED_BACK")

        // 未补充材料不能重审
        assertThat(post("/api/orders/$orderId/re-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
            .statusCode.value()).isEqualTo(409)

        // 患者补充材料
        val sup = post("/api/orders/$orderId/supplement",
            mapOf("url" to "https://img.example.com/rx/diag.jpg", "note" to "补充诊断证明"), "PATIENT", "patient-1")
        assertThat(sup.statusCode.value()).isEqualTo(200)
        assertThat(sup.body!!.get("status").asText()).isEqualTo("RESUPPLIED")

        // 他人不能补充
        assertThat(post("/api/orders/$orderId/supplement",
            mapOf("url" to "https://x/y.jpg"), "PATIENT", "patient-2").statusCode.value()).isEqualTo(403)

        // 药师重新审核通过：库存重锁（锁定时间更新）、医保重新预结算
        val re = post("/api/orders/$orderId/re-review",
            mapOf("approved" to true, "comment" to "剂量已更正为 6 单位，符合说明书"), "PHARMACIST", "pharmacist-1")
        assertThat(re.statusCode.value()).isEqualTo(200)
        val reBody = re.body!!
        assertThat(reBody.get("status").asText()).isEqualTo("RE_REVIEWED")
        val relockId = reBody.get("relockId").asText()
        assertThat(relockId).isNotEqualTo(firstLockId)
        assertThat(reBody.get("relockAt").asLong()).isGreaterThan(0)

        // 重锁后可用量再次占用 3，医保回到 PRE_SETTLED
        stock = get("/api/stock", "WAREHOUSE", "warehouse-1").body!!
        assertThat(stock.first { it.get("batchNo").asText() == "B20260901" }.get("availableQty").asInt()).isEqualTo(3)
        val settle2 = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(settle2.get("settlementStatus").asText()).isEqualTo("PRE_SETTLED")

        // 患者端：看到恢复出库条件，但尚未最终确认，出库仍被拦截
        val pv1 = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        val pr1 = pv1.get("prescriptionReturn")
        assertThat(pr1.get("canResumeOutbound").asBoolean()).isTrue()
        assertThat(pr1.get("resumeCondition").asText()).contains("剂量已更正")
        assertThat(pr1.get("stockLock").get("id").asText()).isEqualTo(relockId)
        assertThat(pr1.get("settlement").get("status").asText()).isEqualTo("PRE_SETTLED")
        assertThat(pv1.get("status").asText()).isEqualTo("RESUME_CONFIRMATION_PENDING")
        assertThat(post("/api/orders/$orderId/outbound", outboundBody(orderId), "WAREHOUSE", "warehouse-1")
            .statusCode.value()).isEqualTo(409)

        // 恢复出库前药师再次确认
        val confirm = post("/api/orders/$orderId/resume-confirmation",
            mapOf("condition" to "按更正后剂量发药并电话告知患者"), "PHARMACIST", "pharmacist-3")
        assertThat(confirm.statusCode.value()).isEqualTo(200)
        assertThat(confirm.body!!.get("status").asText()).isEqualTo("RESUMED")
        val orderAfter = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(orderAfter.get("status").asText()).isEqualTo("PICKED")
        assertThat(orderAfter.get("resumeConfirmedBy").asText()).isEqualTo("pharmacist-3")

        // 恢复出库通知已下发，退回事件关闭
        val notifs2 = get("/api/patient/notifications", "PATIENT", "patient-1").body!!
        assertThat(notifs2.any { it.get("type").asText() == "OUTBOUND_RESUMED" }).isTrue()
        val incidentsAfter = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidentsAfter.filter { it.get("type").asText() == "PRESCRIPTION_RETURNED" }
            .all { it.get("status").asText() == "RESOLVED" }).isTrue()

        // 药师留痕：case 审计轨迹包含关键环节
        val caseDetail = get("/api/orders/$orderId/prescription-return", "PHARMACIST", "pharmacist-1").body!!
        val actions = caseDetail.get("cases").map { c -> c.get("auditTrail").map { a -> a.get("action").asText() } }.first()
        assertThat(actions).contains("INITIATE", "ROLLBACK", "SUPPLEMENT", "RE_LOCK", "RE_REVIEW_PASSED", "RESUME_CONFIRMED")

        // 确认后可正常出库：锁定转扣减，医保正式结算
        val ob = post("/api/orders/$orderId/outbound", outboundBody(orderId), "WAREHOUSE", "warehouse-1")
        assertThat(ob.statusCode.value()).isEqualTo(200)
        val settle3 = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(settle3.get("settlementStatus").asText()).isEqualTo("SETTLED")
        stock = get("/api/stock", "WAREHOUSE", "warehouse-1").body!!
        assertThat(stock.first { it.get("batchNo").asText() == "B20260901" }.get("availableQty").asInt()).isEqualTo(3)
        val finalLocks = get("/api/orders/$orderId/stock-locks", "WAREHOUSE", "warehouse-1").body!!.get("locks")
        assertThat(finalLocks.first { it.get("id").asText() == relockId }.get("status").asText()).isEqualTo("DEDUCTED")
        // 同一 case 已结束，不能再次确认恢复
        assertThat(post("/api/orders/$orderId/resume-confirmation",
            mapOf<String, String>(), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(409)
    }

    @Test
    fun `未拣货退回：仅暂停出库无三项回滚，补充重审确认后回到待出库再拣货出库`() {
        val orderId = createOrder()
        toReady(orderId)

        val ret = post("/api/orders/$orderId/prescription-return",
            mapOf("reason" to "INDICATION_MISMATCH", "detail" to "适应症与诊断不符"), "PHARMACIST", "pharmacist-1")
        assertThat(ret.body!!.get("pickedWhenReturned").asBoolean()).isFalse()
        assertThat(ret.body!!.get("rolledBackLockId").isNull).isTrue()

        // 未拣货无医保预结算，结算状态保持 NONE
        val settle = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(settle.get("settlementStatus").asText()).isEqualTo("NONE")

        post("/api/orders/$orderId/supplement",
            mapOf("url" to "https://img.example.com/rx/new.jpg"), "PATIENT", "patient-1")
        post("/api/orders/$orderId/re-review",
            mapOf("approved" to true, "comment" to "诊断已补充，适应症相符"), "PHARMACIST", "pharmacist-1")
        val confirm = post("/api/orders/$orderId/resume-confirmation",
            mapOf<String, String>(), "PHARMACIST", "pharmacist-1")
        assertThat(confirm.body!!.get("status").asText()).isEqualTo("RESUMED")
        val orderAfter = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(orderAfter.get("status").asText()).isEqualTo("READY_FOR_OUTBOUND")

        // 恢复后正常拣货出库
        restock()
        assertThat(post("/api/orders/$orderId/pick", mapOf("batchNo" to "B20260901"), "WAREHOUSE", "warehouse-1")
            .statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/outbound", outboundBody(orderId), "WAREHOUSE", "warehouse-1")
            .statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `退回重审不通过保持暂停，患者继续补充后通过`() {
        val orderId = createOrder()
        toReady(orderId)
        restock()
        post("/api/orders/$orderId/pick", mapOf("batchNo" to "B20260901"), "WAREHOUSE", "warehouse-1")
        post("/api/orders/$orderId/prescription-return",
            mapOf("reason" to "DOSAGE_MISMATCH"), "PHARMACIST", "pharmacist-1")

        post("/api/orders/$orderId/supplement",
            mapOf("url" to "https://img.example.com/rx/a.jpg"), "PATIENT", "patient-1")
        // 第一次重审不通过 → 回到 OPEN，继续暂停
        val rejected = post("/api/orders/$orderId/re-review",
            mapOf("approved" to false, "comment" to "材料仍无法证明剂量"), "PHARMACIST", "pharmacist-1")
        assertThat(rejected.body!!.get("status").asText()).isEqualTo("OPEN")
        assertThat(get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!
            .get("order").get("status").asText()).isEqualTo("OUTBOUND_PAUSED")
        // 不通过期间库存仍处于回滚释放状态
        val stock = get("/api/stock", "WAREHOUSE", "warehouse-1").body!!
        assertThat(stock.first { it.get("batchNo").asText() == "B20260901" }.get("availableQty").asInt()).isEqualTo(5)

        // 再次补充并通过
        post("/api/orders/$orderId/supplement",
            mapOf("url" to "https://img.example.com/rx/b.jpg"), "PATIENT", "patient-1")
        post("/api/orders/$orderId/re-review",
            mapOf("approved" to true, "comment" to "剂量依据充分"), "PHARMACIST", "pharmacist-1")
        val confirm = post("/api/orders/$orderId/resume-confirmation",
            mapOf<String, String>(), "PHARMACIST", "pharmacist-1")
        assertThat(confirm.body!!.get("status").asText()).isEqualTo("RESUMED")
    }

    @Test
    fun `退回联动边界：非药师不能发起，库存不足重锁失败，无进行中case不能确认恢复`() {
        val orderId = createOrder()
        toReady(orderId)
        restock()
        post("/api/orders/$orderId/pick", mapOf("batchNo" to "B20260901"), "WAREHOUSE", "warehouse-1")

        // 仓库/患者不能发起退回
        assertThat(post("/api/orders/$orderId/prescription-return",
            mapOf("reason" to "DOSAGE_MISMATCH"), "WAREHOUSE", "warehouse-1").statusCode.value()).isEqualTo(403)
        assertThat(post("/api/orders/$orderId/prescription-return",
            mapOf("reason" to "DOSAGE_MISMATCH"), "PATIENT", "patient-1").statusCode.value()).isEqualTo(403)

        post("/api/orders/$orderId/prescription-return",
            mapOf("reason" to "DOSAGE_MISMATCH"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/supplement",
            mapOf("url" to "https://img.example.com/rx/a.jpg"), "PATIENT", "patient-1")

        // 回滚后可用量恢复为 5；用其他两单各锁定 2，占用至剩余 1，使本单重锁（需 2）失败
        val other1 = createOrder(patientId = "patient-9")
        toReady(other1)
        assertThat(post("/api/orders/$other1/pick", mapOf("batchNo" to "B20260901"), "WAREHOUSE", "warehouse-1")
            .statusCode.value()).isEqualTo(200)
        val other2 = createOrder(patientId = "patient-10")
        toReady(other2)
        assertThat(post("/api/orders/$other2/pick", mapOf("batchNo" to "B20260901"), "WAREHOUSE", "warehouse-1")
            .statusCode.value()).isEqualTo(200)
        val stockNow = get("/api/stock", "WAREHOUSE", "warehouse-1").body!!
        assertThat(stockNow.first { it.get("batchNo").asText() == "B20260901" }.get("availableQty").asInt()).isEqualTo(1)

        // 库存不足时重新审核重锁失败，订单仍停留在待重审，不能进入确认环节
        val reFail = post("/api/orders/$orderId/re-review",
            mapOf("approved" to true, "comment" to "剂量更正"), "PHARMACIST", "pharmacist-1")
        assertThat(reFail.statusCode.value()).isEqualTo(409)
        assertThat(post("/api/orders/$orderId/resume-confirmation",
            mapOf<String, String>(), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(409)

        // 无进行中 case 时确认恢复被拒
        val fresh = createOrder(patientId = "patient-8")
        toReady(fresh)
        assertThat(post("/api/orders/$fresh/resume-confirmation",
            mapOf<String, String>(), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(409)
    }
}
