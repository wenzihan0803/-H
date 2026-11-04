package com.pw.admin.controller;

import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import com.pw.order.dto.ComplaintDto;
import com.pw.order.dto.OrderReq;
import com.pw.order.service.ArbitrationService;
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

import java.util.List;

/** 管理端仲裁工单：客服可看，只有管理员/超管能定扣款比例。 */
@Tag(name = "13-仲裁（管理端）", description = "仲裁工单列表与处理")
@RestController
@RequestMapping("/admin/complaints")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminComplaintController {

    private final ArbitrationService arbitrationService;

    @Operation(summary = "仲裁工单列表")
    @GetMapping
    public Result<List<ComplaintDto.View>> list(@RequestParam(required = false) String status) {
        return Result.ok(arbitrationService.list(status));
    }

    @Operation(summary = "处理仲裁", description = "0 = 驳回；1–100 = 退客户比例，剩余全给打手")
    @PostMapping("/{id}/resolve")
    public Result<ComplaintDto.View> resolve(@PathVariable Long id,
                                             @Valid @RequestBody OrderReq.ResolveArbitrationReq req) {
        LoginUser me = SecurityContexts.requireRole(Role.ADMIN, Role.SUPER_ADMIN);
        return Result.ok(arbitrationService.resolve(id, me.getSubjectId(), me.getRole(),
                req.getRefundRate(), req.getNote()));
    }
}
