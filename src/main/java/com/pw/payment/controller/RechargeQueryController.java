package com.pw.payment.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.common.result.Result;
import com.pw.common.security.SecurityContexts;
import com.pw.payment.entity.RechargeOrder;
import com.pw.payment.mapper.RechargeOrderMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 充值套餐与充值记录（真实支付未接入，下单仍走 dev 模拟）。 */
@Tag(name = "23-充值（客户端）", description = "套餐与记录")
@RestController
@RequestMapping("/recharges")
@RequiredArgsConstructor
public class RechargeQueryController {

    private final RechargeOrderMapper rechargeOrderMapper;

    @Operation(summary = "充值套餐")
    @GetMapping("/packages")
    public Result<List<Map<String, Object>>> packages() {
        List<Map<String, Object>> list = new ArrayList<>();
        int[] cents = {1000, 5000, 10000, 200000};
        String[] labels = {"10 元", "50 元", "100 元", "2000 元（自动开通会员）"};
        for (int i = 0; i < cents.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("amount", cents[i]);
            row.put("label", labels[i]);
            list.add(row);
        }
        return Result.ok(list);
    }

    @Operation(summary = "我的充值记录", description = "最近 50 条")
    @GetMapping
    public Result<List<Map<String, Object>>> orders() {
        Long userId = SecurityContexts.requireCustomer().getSubjectId();
        List<RechargeOrder> list = rechargeOrderMapper.selectList(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getUserId, userId)
                .orderByDesc(RechargeOrder::getCreatedAt)
                .last("LIMIT 50"));
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (RechargeOrder order : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", order.getId());
            row.put("rechargeNo", order.getRechargeNo());
            row.put("amount", order.getAmount());
            row.put("status", order.getStatus());
            row.put("createdAt", order.getCreatedAt());
            result.add(row);
        }
        return Result.ok(result);
    }
}
