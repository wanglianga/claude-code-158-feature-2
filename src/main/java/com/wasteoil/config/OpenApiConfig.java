package com.wasteoil.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。Swagger UI: /swagger-ui/index.html，OpenAPI JSON: /v3/api-docs。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI wasteOilOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("城市餐厨废油回收预约称重联单合规追踪服务")
                .description("预约 → 派单 → 核验 → 称重 → 封桶 → 运输 → 二次称重 → 合规闭环 → 结算 全链路 API；"
                        + "含拒收责任判断（证据追溯/定责/额外费用/返运二次处理）。"
                        + "调用时在请求头携带 X-Role 与 X-Account-Id（见 README 测试账号）。")
                .version("1.0.0"));
    }
}
