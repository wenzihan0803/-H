package com.pw.admin.controller;

import com.pw.admin.dto.ReportDto;
import com.pw.admin.service.AdminReportService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 经营报表。 */
@Tag(name = "20-报表（管理端）", description = "订单/GMV/结算/退款/新增，按日拆分")
@RestController
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
@RequireRole({Role.ADMIN, Role.SUPER_ADMIN})
public class AdminReportController {

    private final AdminReportService reportService;

    @Operation(summary = "经营报表", description = "默认本月；结束日期只填开始则到当天")
    @GetMapping
    public Result<ReportDto.View> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(reportService.build(from, to));
    }
}
