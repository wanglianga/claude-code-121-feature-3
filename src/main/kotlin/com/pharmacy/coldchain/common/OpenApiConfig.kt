package com.pharmacy.coldchain.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 3.x 文档配置：
 *   - Swagger UI: /swagger-ui.html
 *   - OpenAPI JSON: /v3/api-docs
 * 所有接口通过 X-User-Id / X-Role 请求头鉴权，这里统一声明为必填请求头。
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun apiInfo(): OpenAPI = OpenAPI().info(
        Info()
            .title("药店冷藏药品配送签收与温控赔付服务 API")
            .description(
                "处方审核与退回联动、出库、冷链配送监控、患者签收、异常协同、赔付结算。\n\n" +
                    "鉴权：每个请求需携带 `X-User-Id`（如 pharmacist-1）与 `X-Role`" +
                    "（PHARMACIST/WAREHOUSE/RIDER/PATIENT/CUSTOMER_SERVICE/INSURANCE/COMPENSATION/ADMIN）。"
            )
            .version("1.0.0")
    )

    @Bean
    fun authHeaderCustomizer(): OperationCustomizer = OperationCustomizer { operation, _ ->
        val userIdHeader = Parameter()
            .`in`("header")
            .name("X-User-Id")
            .description("用户标识，如 pharmacist-1")
            .required(true)
            .schema(StringSchema())
        val roleHeader = Parameter()
            .`in`("header")
            .name("X-Role")
            .description("角色：PHARMACIST / WAREHOUSE / RIDER / PATIENT / CUSTOMER_SERVICE / INSURANCE / COMPENSATION / ADMIN")
            .required(true)
            .schema(StringSchema())
        operation.addParametersItem(userIdHeader).addParametersItem(roleHeader)
        operation
    }
}
