package com.pw.admin.controller;

import com.pw.admin.dto.DictDto;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端字典下发：权限角色映射（前端登录后拉取渲染菜单）。 */
@Tag(name = "25-字典（管理端）", description = "权限字典下发")
@RestController
@RequestMapping("/admin/dict")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminDictController {

    @Operation(summary = "角色→权限映射", description = "登录后拉取一次；SUPER_ADMIN 为通配 *")
    @GetMapping("/permissions")
    public Result<DictDto.RolePerms> permissions() {
        return Result.ok(new DictDto.RolePerms(DictDto.ROLE_PERMS));
    }
}
