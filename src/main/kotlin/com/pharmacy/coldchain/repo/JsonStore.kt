package com.pharmacy.coldchain.repo

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis JSON 存储助手。
 * key 规划：
 *   seq:{prefix}                 自增序列（订单 O、事件 I、回访 F、照片 P、重判 RJ）
 *   order:{id}                   订单 JSON
 *   idx:all / idx:status:{s} / idx:patient:{pid}   订单索引集合
 *   telemetry:{orderId}          遥测 list
 *   incident:{id} + idx:incident:order:{orderId}   异常事件
 *   rejudgment:{orderId}         药师重判 list
 *   resolution:{orderId}         最终处理结果
 *   payment:{orderId}            医保自费拆分、退款与预结算状态
 *   lock:{orderId}               拣货库存锁定（退回联动回滚/重新锁定）
 *   rxreturn:{orderId}           处方退回联动记录
 *   audit:{orderId}              药师留痕
 *   notify:{patientId} + idx:notify:order:{orderId}  患者通知
 *   box:{boxNo} + idx:boxes      保温箱
 *   training:{riderId}           骑手培训 list
 *   followup:{orderId}           用药回访 list
 *   rxphoto:{orderId}            处方照片 list
 */
@Component
class JsonStore(
    private val redis: StringRedisTemplate,
    private val om: ObjectMapper
) {
    fun set(key: String, value: Any) {
        redis.opsForValue().set(key, om.writeValueAsString(value))
    }

    fun <T> get(key: String, type: Class<T>): T? =
        redis.opsForValue().get(key)?.let { om.readValue(it, type) }

    fun del(key: String) {
        redis.delete(key)
    }

    fun nextId(prefix: String): String =
        prefix + (redis.opsForValue().increment("seq:$prefix") ?: 0L).toString()

    fun sadd(key: String, member: String) {
        redis.opsForSet().add(key, member)
    }

    fun srem(key: String, member: String) {
        redis.opsForSet().remove(key, member)
    }

    fun smembers(key: String): Set<String> =
        redis.opsForSet().members(key) ?: emptySet()

    fun rpushJson(key: String, value: Any) {
        redis.opsForList().rightPush(key, om.writeValueAsString(value))
    }

    fun <T> lrangeJson(key: String, type: Class<T>): List<T> =
        (redis.opsForList().range(key, 0, -1) ?: emptyList()).map { om.readValue(it, type) }
}

// reified 便捷方法（扩展函数不受 all-open 影响）
inline fun <reified T> JsonStore.get(key: String): T? = get(key, T::class.java)

inline fun <reified T> JsonStore.lrangeJson(key: String): List<T> = lrangeJson(key, T::class.java)
