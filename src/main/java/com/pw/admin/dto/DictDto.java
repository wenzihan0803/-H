package com.pw.admin.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端字典下发 DTO。
 * 角色 → 权限映射的唯一来源在后端；前端登录后拉取一次用于菜单渲染与按钮显隐。
 * 后端接口级 403 校验仍是安全边界，字典下发仅是体验层。
 */
public class DictDto {

    public record RolePerms(Map<String, List<String>> rolePerms) {
    }

    /** 与前端旧版 shared/dict/permissions.ts 的 ROLE_PERMS 保持一致（去重后） */
    public static final Map<String, List<String>> ROLE_PERMS = buildRolePerms();

    private static Map<String, List<String>> buildRolePerms() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // 客服：零资金权限、看不到流水
        m.put("CS", List.of(
                "workbench:read", "order:read", "order:handover", "order:assign",
                "refund:read", "refund:apply", "booster:read", "booster:review",
                "complaint:read", "complaint:process", "user:read", "im:read",
                "punishment:read", "punishment:apply"));
        // 管理员：全量业务权限
        m.put("ADMIN", List.of(
                "workbench:read", "order:read", "order:handover", "order:assign", "order:reassign",
                "refund:read", "refund:apply", "refund:approve", "withdraw:read", "withdraw:approve",
                "ledger:read", "settlement:read", "booster:read", "booster:review",
                "member:read", "member:approve", "punishment:read", "punishment:apply",
                "sku:read", "complaint:read", "complaint:process", "complaint:close", "complaint:resolve",
                "user:read", "log:read", "im:read", "banner:read", "banner:write", "report:read"));
        // 超管：通配
        m.put("SUPER_ADMIN", List.of("*"));
        return Map.copyOf(m);
    }
}
