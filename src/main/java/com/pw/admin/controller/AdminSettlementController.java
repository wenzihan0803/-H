package com.pw.admin.controller;

import com.pw.admin.dto.SettlementSummaryDto;
import com.pw.admin.service.AdminSettlementService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 管理端「合计」：已结算订单的抽成明细、汇总与导出。 */
@Tag(name = "14-合计（管理端）", description = "按打手/时间汇总平台与打手应得")
@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminSettlementController {

    private final AdminSettlementService settlementService;

    @Operation(summary = "合计明细", description = "按打手 + 结算时间区间查询")
    @GetMapping("/summary")
    public Result<SettlementSummaryDto.Result> summary(
            @RequestParam(required = false) Long boosterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(settlementService.summary(boosterId, from, to));
    }

    @Operation(summary = "合计导出 CSV", description = "columns 逗号分隔，不传导出全部列")
    @GetMapping("/summary/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long boosterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String columns) {
        List<String> cols = columns == null || columns.isBlank()
                ? List.of()
                : List.of(columns.split(","));
        byte[] csv = settlementService.exportCsv(boosterId, from, to, cols);
        String filename = "合计_" + (from == null ? "全部" : from) + "_" + (to == null ? "至今" : to) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
