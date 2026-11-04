package com.pw.admin.controller;

import com.pw.admin.dto.WorkbenchDto;
import com.pw.admin.service.AdminWorkbenchService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端工作台。 */
@Tag(name = "15-工作台（管理端）", description = "今日指标、待办计数、处置配额")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminWorkbenchController {

    private final AdminWorkbenchService workbenchService;

    @Operation(summary = "工作台数据")
    @GetMapping("/workbench")
    public Result<WorkbenchDto.View> workbench() {
        return Result.ok(workbenchService.build());
    }
}
