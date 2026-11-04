package com.pw.gold.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.common.result.Result;
import com.pw.common.security.SecurityContexts;
import com.pw.gold.entity.CustomerGoldAccount;
import com.pw.gold.entity.CustomerGoldTxn;
import com.pw.gold.mapper.CustomerGoldAccountMapper;
import com.pw.gold.mapper.CustomerGoldTxnMapper;
import com.pw.member.entity.MemberAccount;
import com.pw.member.mapper.MemberAccountMapper;
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

/** 客户金砖账户与流水。 */
@Tag(name = "22-金砖（客户端）", description = "余额与流水")
@RestController
@RequestMapping("/gold")
@RequiredArgsConstructor
public class GoldQueryController {

    private final CustomerGoldAccountMapper accountMapper;
    private final CustomerGoldTxnMapper txnMapper;
    private final MemberAccountMapper memberAccountMapper;

    @Operation(summary = "金砖账户")
    @GetMapping("/account")
    public Result<Map<String, Object>> account() {
        Long userId = SecurityContexts.requireCustomer().getSubjectId();
        CustomerGoldAccount account = accountMapper.selectById(userId);
        MemberAccount member = memberAccountMapper.selectById(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balance", account == null ? 0L : account.getBalance());
        data.put("totalRecharged", account == null ? 0L : account.getTotalRecharged());
        data.put("memberLevel", member == null ? MemberAccount.LEVEL_NORMAL : member.getLevelOrDefault());
        return Result.ok(data);
    }

    @Operation(summary = "金砖流水", description = "最近 100 条，倒序")
    @GetMapping("/txns")
    public Result<List<Map<String, Object>>> txns() {
        Long userId = SecurityContexts.requireCustomer().getSubjectId();
        List<CustomerGoldTxn> list = txnMapper.selectList(new LambdaQueryWrapper<CustomerGoldTxn>()
                .eq(CustomerGoldTxn::getUserId, userId)
                .orderByDesc(CustomerGoldTxn::getCreatedAt)
                .orderByDesc(CustomerGoldTxn::getId)
                .last("LIMIT 100"));
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (CustomerGoldTxn txn : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", txn.getId());
            row.put("bizType", txn.getBizType());
            row.put("bizNo", txn.getBizNo());
            row.put("amount", txn.getAmount());
            row.put("balanceAfter", txn.getBalanceAfter());
            row.put("remark", txn.getRemark());
            row.put("createdAt", txn.getCreatedAt());
            result.add(row);
        }
        return Result.ok(result);
    }
}
