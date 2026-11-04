package com.pw.user.controller;

import com.pw.auth.dto.UserProfileVO;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import com.pw.common.util.IdGenerator;
import com.pw.user.dto.GameProfileReq;
import com.pw.user.dto.UpdateProfileReq;
import com.pw.user.dto.VerifyReq;
import com.pw.user.entity.UserGameProfile;
import com.pw.user.mapper.UserGameProfileMapper;
import com.pw.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户接口。对应 04 §3.1「认证与用户」的 users 部分。
 *
 * <p><b>防 IDOR</b>（01 §2、§17）：所有 /users/me/** 接口的 userId 一律取自登录态，
 * <b>绝不接受请求参数传入的 userId</b>。游戏资料按 ID 操作时额外校验归属。</p>
 *
 * <p>游戏资料 CRUD 仅客户可用（04 §3.1 权限列标 C）。</p>
 */
@Tag(name = "02-用户", description = "实名、资料、游戏档案管理")
@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserGameProfileMapper gameProfileMapper;
    private final IdGenerator idGenerator;

    @Operation(summary = "提交实名认证", description = "本期为 dev 直通过实现，上线前接三方二要素 + 人脸")
    @RequireRole({Role.CUSTOMER, Role.BOOSTER})
    @PostMapping("/verify")
    public Result<UserService.VerifyStatusVO> submitVerify(@Valid @RequestBody VerifyReq req) {
        Long userId = SecurityContexts.requireSubjectId();
        return Result.ok(userService.submitVerify(userId, req.getRealName(), req.getIdCard(), req.getFaceToken()));
    }

    @Operation(summary = "查询实名状态")
    @RequireRole({Role.CUSTOMER, Role.BOOSTER})
    @GetMapping("/verify")
    public Result<UserService.VerifyStatusVO> getVerify() {
        return Result.ok(userService.getVerifyStatus(SecurityContexts.requireSubjectId()));
    }

    @Operation(summary = "查询本人资料", description = "客户侧含金砖余额")
    @RequireRole({Role.CUSTOMER, Role.BOOSTER})
    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile() {
        LoginUser loginUser = SecurityContexts.require();
        return Result.ok(userService.buildProfile(loginUser.getSubjectId(), loginUser.isCustomer()));
    }

    @Operation(summary = "更新本人资料", description = "昵称与头像，至少填一项")
    @RequireRole({Role.CUSTOMER, Role.BOOSTER})
    @PutMapping("/profile")
    public Result<UserProfileVO> updateProfile(@Valid @RequestBody UpdateProfileReq req) {
        if (req.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请至少填写昵称或头像其中一项");
        }
        return Result.ok(userService.updateProfile(SecurityContexts.requireSubjectId(),
                req.getNickname(), req.getAvatar()));
    }

    @Operation(summary = "查询游戏资料列表", description = "仅客户可用")
    @RequireRole(Role.CUSTOMER)
    @GetMapping("/game-profiles")
    public Result<List<GameProfileReq>> listGameProfiles() {
        Long userId = SecurityContexts.requireSubjectId();
        List<UserGameProfile> list = gameProfileMapper.selectList(new LambdaQueryWrapper<UserGameProfile>()
                .eq(UserGameProfile::getUserId, userId)
                .orderByDesc(UserGameProfile::getIsDefault)
                .orderByDesc(UserGameProfile::getCreatedAt));
        return Result.ok(list.stream().map(UserController::toVO).toList());
    }

    @Operation(summary = "新增游戏资料", description = "仅客户可用；同游戏同账号不可重复录入")
    @RequireRole(Role.CUSTOMER)
    @PostMapping("/game-profiles")
    @Transactional(rollbackFor = Exception.class)
    public Result<GameProfileReq> createGameProfile(@Valid @RequestBody GameProfileReq req) {
        Long userId = SecurityContexts.requireSubjectId();

        UserGameProfile duplicated = gameProfileMapper.selectOne(new LambdaQueryWrapper<UserGameProfile>()
                .eq(UserGameProfile::getUserId, userId)
                .eq(UserGameProfile::getGameId, req.getGameId())
                .eq(UserGameProfile::getAccountId, req.getAccountId())
                .last("LIMIT 1"));
        if (duplicated != null) {
            throw BizException.of(ErrorCode.DUPLICATE_SUBMIT, "该游戏账号已添加，请勿重复录入");
        }

        boolean asDefault = Boolean.TRUE.equals(req.getIsDefault());
        if (asDefault) {
            clearDefault(userId);
        } else if (isFirstProfile(userId)) {
            // 首条资料自动设为默认，避免下单时无默认账号可选
            asDefault = true;
        }

        UserGameProfile entity = new UserGameProfile();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setGameId(req.getGameId().trim());
        entity.setAccountId(req.getAccountId().trim());
        entity.setServer(trimOrNull(req.getServer()));
        entity.setNickname(trimOrNull(req.getNickname()));
        entity.setIsDefault(asDefault ? 1 : 0);
        gameProfileMapper.insert(entity);
        return Result.ok(toVO(entity));
    }

    @Operation(summary = "修改游戏资料", description = "仅客户可用，且只能改本人资料")
    @RequireRole(Role.CUSTOMER)
    @PutMapping("/game-profiles/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<GameProfileReq> updateGameProfile(@PathVariable("id") Long id,
                                                    @Valid @RequestBody GameProfileReq req) {
        Long userId = SecurityContexts.requireSubjectId();
        UserGameProfile existing = requireOwnedGameProfile(userId, id);

        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearDefault(userId);
        }

        UserGameProfile update = new UserGameProfile();
        update.setId(existing.getId());
        update.setGameId(req.getGameId().trim());
        update.setAccountId(req.getAccountId().trim());
        update.setServer(trimOrNull(req.getServer()));
        update.setNickname(trimOrNull(req.getNickname()));
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            update.setIsDefault(1);
        }
        gameProfileMapper.updateById(update);

        return Result.ok(toVO(gameProfileMapper.selectById(existing.getId())));
    }

    @Operation(summary = "删除游戏资料", description = "仅客户可用，且只能删本人资料")
    @RequireRole(Role.CUSTOMER)
    @DeleteMapping("/game-profiles/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteGameProfile(@PathVariable("id") Long id) {
        Long userId = SecurityContexts.requireSubjectId();
        UserGameProfile existing = requireOwnedGameProfile(userId, id);
        boolean wasDefault = existing.getIsDefault() != null && existing.getIsDefault() == 1;
        gameProfileMapper.deleteById(id);

        // 删掉默认资料后，把最新一条顶上，避免下单时无默认账号
        if (wasDefault) {
            UserGameProfile next = gameProfileMapper.selectOne(new LambdaQueryWrapper<UserGameProfile>()
                    .eq(UserGameProfile::getUserId, userId)
                    .orderByDesc(UserGameProfile::getCreatedAt)
                    .last("LIMIT 1"));
            if (next != null) {
                UserGameProfile update = new UserGameProfile();
                update.setId(next.getId());
                update.setIsDefault(1);
                gameProfileMapper.updateById(update);
            }
        }
        return Result.ok();
    }

    /** 归属校验：资料必须属于当前登录客户，否则 40301。这是防 IDOR 的关键一步。 */
    private UserGameProfile requireOwnedGameProfile(Long userId, Long id) {
        UserGameProfile existing = gameProfileMapper.selectById(id);
        if (existing == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "游戏资料不存在，请返回列表刷新");
        }
        if (!existing.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.FORBIDDEN, "无法操作他人的游戏资料");
        }
        return existing;
    }

    private boolean isFirstProfile(Long userId) {
        return gameProfileMapper.selectCount(new LambdaQueryWrapper<UserGameProfile>()
                .eq(UserGameProfile::getUserId, userId)) == 0L;
    }

    private void clearDefault(Long userId) {
        gameProfileMapper.update(null, new LambdaUpdateWrapper<UserGameProfile>()
                .eq(UserGameProfile::getUserId, userId)
                .eq(UserGameProfile::getIsDefault, 1)
                .set(UserGameProfile::getIsDefault, 0));
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Entity → VO。Service 不返回 Entity（01 §1），Controller 出参统一用 VO。 */
    private static GameProfileReq toVO(UserGameProfile entity) {
        GameProfileReq vo = new GameProfileReq();
        vo.setId(entity.getId());
        vo.setGameId(entity.getGameId());
        vo.setAccountId(entity.getAccountId());
        vo.setServer(entity.getServer());
        vo.setNickname(entity.getNickname());
        vo.setIsDefault(entity.getIsDefault() != null && entity.getIsDefault() == 1);
        return vo;
    }
}
