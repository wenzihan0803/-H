package com.pw.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 功能开关。对应 {@code pw.feature.*}。
 *
 * <p>口径来源：技术规范/09-本地开发基线与Mock策略.md §2。</p>
 *
 * <ul>
 *   <li>{@code realname=false}：实名 guard 放行（本期不做，上线前启用）；</li>
 *   <li>{@code boosterApplyFull=false}：入驻六道关完整流程关闭（先 mock）；</li>
 *   <li>{@code minorCheck=false}：未成年拦截随实名一并后补。</li>
 * </ul>
 *
 * <p><b>上线前必须回补</b>（09 §5）：realname 置 true 并接三方，回归 ORD-05/06 用例。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pw.feature")
public class FeatureProperties {

    /** 实名校验开关。false 时下单/入驻的实名 guard 直接放行。 */
    private boolean realname = false;

    /** 打手入驻六道关完整流程开关。false 时走 mock（种子数据直接建 ACTIVE 打手）。 */
    private boolean boosterApplyFull = false;

    /** 未成年拦截开关。false 时不校验年龄。 */
    private boolean minorCheck = false;
}
