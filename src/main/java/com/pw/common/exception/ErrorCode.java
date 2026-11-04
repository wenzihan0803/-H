package com.pw.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p>唯一来源：技术规范/04-接口规范与接口清单.md §5 错误码表。
 * 新增错误码属于封板变更，必须走评审（见 08 §6 变更控制）。</p>
 *
 * <p>提示文案遵循「原因 + 下一步」。</p>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, HttpStatus.OK, "成功"),

    // ---------- 协议层 ----------
    PARAM_ERROR(40001, HttpStatus.BAD_REQUEST, "参数有误，请检查后重新提交"),
    UNAUTHORIZED(40101, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),
    TOKEN_INVALID(40102, HttpStatus.UNAUTHORIZED, "登录凭证无效，请重新登录"),
    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "没有操作权限，请联系管理员"),
    ACCOUNT_RESTRICTED(40302, HttpStatus.FORBIDDEN, "账号已被限制使用，请联系客服"),
    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "内容不存在或已被删除，请返回列表刷新"),
    STATE_CONFLICT(40901, HttpStatus.CONFLICT, "状态已变更，请刷新后重试"),
    DUPLICATE_SUBMIT(40902, HttpStatus.CONFLICT, "请勿重复提交，已为你保留上一次结果"),
    TOO_MANY_REQUESTS(42901, HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试"),
    SYSTEM_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试或联系客服"),

    // ---------- 业务层 ----------
    REFUND_FAILED(60002, HttpStatus.BAD_REQUEST, "退款失败，请稍后重试或联系客服"),
    HOLD_EMPTY(60003, HttpStatus.BAD_REQUEST, "托管金已清零或已退款，请刷新订单查看"),
    WITHDRAW_REJECTED_BANNED(60005, HttpStatus.BAD_REQUEST, "账号封禁中无法提现，进行中提现已暂缓"),
    MEMBER_NOT_QUALIFIED(60006, HttpStatus.BAD_REQUEST, "暂不满足会员条件，单笔充值满 2,000 元后可申请"),
    INVITE_REWARD_UNAVAILABLE(60007, HttpStatus.BAD_REQUEST, "邀请奖励当前不可用，请查看活动规则"),
    GOLD_NOT_ENOUGH(60008, HttpStatus.BAD_REQUEST, "金砖余额不足，请先充值"),
    ORDER_ALREADY_SETTLED(60009, HttpStatus.CONFLICT, "订单已结算不可退款，请联系客服处理"),
    CONFIRM_NOT_ALLOWED(60010, HttpStatus.CONFLICT, "当前状态不可确认，请刷新订单详情"),
    HANDOVER_ACK_NOT_ALLOWED(60011, HttpStatus.CONFLICT, "交接状态不允许确认，请刷新后重试"),
    RECHARGE_NOT_CREDITED(60012, HttpStatus.CONFLICT, "充值尚未到账，请稍后刷新余额"),
    REFUND_DUPLICATED(60013, HttpStatus.CONFLICT, "该订单已有退款记录，请查看退款详情"),
    REFUND_SERVICE_STARTED(60014, HttpStatus.CONFLICT, "服务已开始无法自助全额退，请联系客服协商"),
    REFUND_PENDING_EXISTS(60015, HttpStatus.CONFLICT, "已有待审批的退款申请，请等待主管审批"),
    REFUND_APPROVER_FORBIDDEN(60016, HttpStatus.FORBIDDEN, "非主管不能审批退款，请联系管理员处理"),
    REFUND_RATE_INVALID(60017, HttpStatus.BAD_REQUEST, "退款比例非法，仅支持 1%–100%"),
    REVIEW_DUPLICATED(60018, HttpStatus.CONFLICT, "该订单已评价，不可重复提交"),
    REVIEW_NOT_ALLOWED(60019, HttpStatus.BAD_REQUEST, "当前状态不可评价，仅已完成订单可评价"),
    ARBITRATION_NOT_ALLOWED(60020, HttpStatus.BAD_REQUEST, "当前状态不可申请仲裁，仅已完成订单可申请"),
    ARBITRATION_EXISTS(60021, HttpStatus.CONFLICT, "该订单已有仲裁处理中，请查看工单进度"),
    ;

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
