package com.pw.admin.controller;

import com.pw.common.enums.Role;
import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import com.pw.im.dto.ImDto;
import com.pw.im.service.AdminImService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端客服工作台：客户消息（左会话列表 + 右聊天框）。v1 轮询模式。 */
@Tag(name = "24-消息（客服）", description = "客户会话列表、会话详情、回复、未读总数")
@RestController
@RequestMapping("/admin/im")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminImController {

    private final AdminImService adminImService;

    @Operation(summary = "会话列表", description = "最近消息在前；支持昵称模糊/手机号精确搜索")
    @GetMapping("/conversations")
    public Result<PageResult<ImDto.AdminConversationRow>> conversations(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(adminImService.conversations(keyword, page, pageSize));
    }

    @Operation(summary = "客服端未读总数", description = "侧栏「客户消息」角标")
    @GetMapping("/unread-total")
    public Result<Long> unreadTotal() {
        return Result.ok(adminImService.unreadTotal());
    }

    @Operation(summary = "会话详情", description = "聊天框头部：客户身份 + 最近一单")
    @GetMapping("/conversations/{id}")
    public Result<ImDto.AdminConversationDetail> detail(@PathVariable Long id) {
        return Result.ok(adminImService.detail(id));
    }

    @Operation(summary = "会话历史", description = "最新在前分页；客服拉取即清零该会话未读")
    @GetMapping("/conversations/{id}/messages")
    public Result<PageResult<ImDto.MessageView>> messages(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(adminImService.messages(id, page, pageSize));
    }

    @Operation(summary = "回复客户")
    @PostMapping("/conversations/{id}/reply")
    public Result<ImDto.MessageView> reply(@PathVariable Long id,
                                           @Valid @RequestBody ImDto.SendReq req) {
        LoginUser me = SecurityContexts.requireRole(Role.CS, Role.ADMIN, Role.SUPER_ADMIN);
        return Result.ok(adminImService.reply(id, me.getSubjectId(), me.getUsername(), req.getContent()));
    }
}
