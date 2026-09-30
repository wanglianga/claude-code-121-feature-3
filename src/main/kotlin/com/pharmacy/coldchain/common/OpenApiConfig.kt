package com.pharmacy.coldchain.common

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 3.x 文档元数据。
 * 访问：/v3/api-docs（JSON）、/swagger-ui.html（Swagger UI）。
 * 鉴权通过请求头 X-User-Id / X-Role 声明，文档中以 apiHeader 方案说明。
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("药店冷藏药品配送签收与温控赔付服务")
                .version("1.0.0")
                .description(
                    "冷藏药品处方审核、拣货/出库、配送温控、患者签收、处方退回联动（库存锁定/医保结算/患者通知回滚与恢复）、赔付处理。\n\n" +
                        "所有业务接口需请求头 X-User-Id 与 X-Role，例如 pharmacist-1 / PHARMACIST。"
                )
        )
        .components(
            Components()
                .addSecuritySchemes(
                    "userId", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("X-User-Id")
                )
                .addSecuritySchemes(
                    "role", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("X-Role")
                )
        )
}
