package com.pw.im.controller;

import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.SecurityContexts;
import com.pw.im.dto.ImDto;
import com.pw.im.service.ImService;
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

/**
 * 客户端消息中心：客服聊天 + 站内通知。v1 轮询模式。
 *
 * <p>客户与官方客服共享一条会话（懒创建），消息页同时展示会话与系统通知。</p>
 */
@Tag(name = "24-消息（客户端）", description = "消息汇总、客服会话、站内通知")
@RestController
@RequestMapping("/im")
@RequiredArgsConstructor
public class ImController {

    private final ImService imService;

    @Operation(summary = "消息汇总", description = "tab 角标 = 聊天未读 + 通知未读")
    @GetMapping("/summary")
    public Result<ImDto.SummaryView> summary() {
        return Result.ok(imService.summary(SecurityContexts.requireCustomer().getSubjectId()));
    }

    @Operation(summary = "我的客服会话", description = "首次进入消息页调用，不存在则懒创建")
    @GetMapping("/conversation")
    public Result<ImDto.MyConversationView> myConversation() {
        return Result.ok(imService.myConversation(SecurityContexts.requireCustomer().getSubjectId()));
    }

    @Operation(summary = "会话历史", description = "最新在前分页；拉取即清零聊天未读")
    @GetMapping("/conversation/{id}/messages")
    public Result<PageResult<ImDto.MessageView>> messages(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(imService.messages(id, SecurityContexts.requireCustomer().getSubjectId(),
                page, pageSize));
    }

    @Operation(summary = "发送消息给客服")
    @PostMapping("/conversation/{id}/messages")
    public Result<ImDto.MessageView> send(@PathVariable Long id,
                                          @Valid @RequestBody ImDto.SendReq req) {
        return Result.ok(imService.sendFromCustomer(id, SecurityContexts.requireCustomer().getSubjectId(),
                req.getContent()));
    }

    @Operation(summary = "通知列表", description = "最新在前分页")
    @GetMapping("/notifications")
    public Result<PageResult<ImDto.NotifyRow>> notifications(@RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(imService.notifications(SecurityContexts.requireCustomer().getSubjectId(),
                page, pageSize));
    }

    @Operation(summary = "通知已读")
    @PostMapping("/notifications/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        imService.markRead(SecurityContexts.requireCustomer().getSubjectId(), id);
        return Result.ok(null);
    }

    @Operation(summary = "全部已读")
    @PostMapping("/notifications/read-all")
    public Result<Void> markAllRead() {
        imService.markAllRead(SecurityContexts.requireCustomer().getSubjectId());
        return Result.ok(null);
    }
}
