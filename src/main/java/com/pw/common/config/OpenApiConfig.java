package com.pw.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 *
 * <p>口径来源：技术规范/04 §6「生产关闭 Swagger UI；测试环境使用 springdoc」。</p>
 *
 * <p>注意：04 §2 的 TypeScript 类型是接口 schema 的唯一人工基线，
 * 代码生成的 OpenAPI 必须与其字段一致；本配置只负责文档外壳。</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI pwOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("三角洲陪玩平台 API")
                        .version("v1")
                        .description("""
                                接口口径唯一来源：技术规范/04-接口规范与接口清单.md

                                通用约定：
                                - 金额整数分（10000 = 100 元），1 金砖 = 1 元
                                - ID 为字符串雪花 ID
                                - 时间为 ISO 8601 +08:00
                                - 创建订单需带 Idempotency-Key 请求头
                                """))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authorization: Bearer <accessToken>")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
