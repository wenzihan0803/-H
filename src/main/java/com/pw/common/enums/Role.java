package com.pw.common.enums;

import lombok.Getter;

/**
 * 全局角色枚举。
 *
 * <p>口径来源：技术规范/04 §1.3、/01 §2 JWT claims {@code role}。</p>
 *
 * <p>客户与打手 token 分离；切换身份需重新签发。管理端为 CS/ADMIN/SUPER_ADMIN 三级。</p>
 */
@Getter
public enum Role {

    /** 小程序客户 */
    CUSTOMER(false, true),
    /** 小程序打手 */
    BOOSTER(false, true),
    /** 客服：零资金权限，可发起争议退款、处理交接与仲裁 */
    CS(true, false),
    /** 管理员（主管）：可审批退款、确认提现、查看流水 */
    ADMIN(true, false),
    /** 超管：唯一，可改类目/SKU/Banner，可代审 */
    SUPER_ADMIN(true, false),
    ;

    /** 是否管理端角色 */
    private final boolean adminSide;
    /** 是否小程序端角色 */
    private final boolean appSide;

    Role(boolean adminSide, boolean appSide) {
        this.adminSide = adminSide;
        this.appSide = appSide;
    }

    /** 是否具备退款审批权（主管）。客服不可审批。 */
    public boolean canApproveRefund() {
        return this == ADMIN || this == SUPER_ADMIN;
    }

    /** 是否可查看管理员流水页（客服 403）。 */
    public boolean canViewLedger() {
        return this == ADMIN || this == SUPER_ADMIN;
    }

    /** 是否可配置类目 / SKU / Banner（仅超管）。 */
    public boolean canManageCatalog() {
        return this == SUPER_ADMIN;
    }

    public static Role of(String value) {
        if (value == null) {
            return null;
        }
        for (Role role : values()) {
            if (role.name().equalsIgnoreCase(value)) {
                return role;
            }
        }
        return null;
    }
}
