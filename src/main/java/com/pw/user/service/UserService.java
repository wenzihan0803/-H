package com.pw.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.auth.dto.UserProfileVO;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.CryptoUtil;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MaskUtil;
import com.pw.gold.entity.CustomerGoldAccount;
import com.pw.gold.mapper.CustomerGoldAccountMapper;
import com.pw.member.entity.MemberAccount;
import com.pw.member.mapper.MemberAccountMapper;
import com.pw.user.entity.User;
import com.pw.user.entity.UserVerify;
import com.pw.user.mapper.UserMapper;
import com.pw.user.mapper.UserVerifyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户服务：查/建用户、资料装配、实名。
 *
 * <p>约束（01 §1）：Controller 不写业务；Service 不返回 Entity，对外只返回 VO。</p>
 *
 * <p><b>敏感字段处理</b>（01 §17）：手机号加密存 {@code phone_cipher}、
 * 检索用 {@code phone_hash}；接口输出一律走 {@link MaskUtil} 脱敏。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserVerifyMapper userVerifyMapper;
    private final CustomerGoldAccountMapper goldAccountMapper;
    private final MemberAccountMapper memberAccountMapper;
    private final IdGenerator idGenerator;
    private final CryptoUtil cryptoUtil;

    /**
     * 按 openid 查用户，不存在则创建。
     *
     * <p>首次登录同时初始化金砖账户与会员账户，保证后续下单/充值不因账户缺失失败。
     * 并发首登由 {@code uk_openid} 唯一索引兜底：插入冲突时回查已存在的记录。</p>
     *
     * @param openid  微信 openid，必填
     * @param unionid 开放平台 unionid，可为 null
     * @return 用户实体（内部流转用；对外接口一律转 VO）
     */
    @Transactional(rollbackFor = Exception.class)
    public User getOrCreateByOpenid(String openid, String unionid) {
        User existing = findByOpenid(openid);
        if (existing != null) {
            // 老用户补 unionid（早期可能没拿到）
            if (unionid != null && existing.getUnionid() == null) {
                User update = new User();
                update.setId(existing.getId());
                update.setUnionid(unionid);
                userMapper.updateById(update);
                existing.setUnionid(unionid);
            }
            return existing;
        }

        User user = new User();
        user.setId(idGenerator.nextId());
        user.setOpenid(openid);
        user.setUnionid(unionid);
        user.setNickname(defaultNickname(user.getId()));
        user.setStatus(User.STATUS_NORMAL);
        user.setVerified(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 并发首登：另一线程已插入，回查即可
            log.info("并发创建用户，回查已存在记录 openid={}", MaskUtil.forLog(openid));
            User concurrent = findByOpenid(openid);
            if (concurrent == null) {
                throw BizException.of(ErrorCode.SYSTEM_ERROR, "登录异常，请稍后重试");
            }
            return concurrent;
        }
        initAccounts(user.getId());
        log.info("新用户创建成功 userId={}", user.getId());
        return user;
    }

    /**
     * 按手机号查用户，不存在则创建。
     *
     * <p>口径：手机号只以 {@code phone_cipher}(AES-GCM) 与 {@code phone_hash}(SHA-256) 落库，
     * 明文字段不落库、不进日志（01 §17）。openid 用 {@code ph_} + 哈希前缀占位，
     * 保证同一手机号多次登录命中同一用户。</p>
     *
     * @param phone 11 位手机号（调用方已做格式校验）
     * @return 用户实体
     */
    @Transactional(rollbackFor = Exception.class)
    public User getOrCreateByPhone(String phone) {
        String phoneHash = cryptoUtil.sha256(phone);
        User existing = findByPhoneHash(phoneHash);
        if (existing != null) {
            return existing;
        }

        User user = new User();
        user.setId(idGenerator.nextId());
        user.setOpenid("ph_" + phoneHash.substring(0, 32));
        // 手机号注册默认昵称带尾号，比纯 userId 更好认（如 玩家5053）
        user.setNickname("玩家" + phone.substring(phone.length() - 4));
        user.setStatus(User.STATUS_NORMAL);
        user.setVerified(0);
        user.setPhoneHash(phoneHash);
        user.setPhoneCipher(cryptoUtil.encrypt(phone));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            User concurrent = findByPhoneHash(phoneHash);
            if (concurrent == null) {
                throw BizException.of(ErrorCode.SYSTEM_ERROR, "登录异常，请稍后重试");
            }
            return concurrent;
        }
        initAccounts(user.getId());
        log.info("手机号用户创建成功 userId={} phone={}", user.getId(), MaskUtil.maskPhone(phone));
        return user;
    }

    /** 初始化金砖账户与会员账户。重复调用安全（主键冲突即跳过）。 */
    @Transactional(rollbackFor = Exception.class)
    public void initAccounts(Long userId) {
        if (goldAccountMapper.selectById(userId) == null) {
            CustomerGoldAccount account = new CustomerGoldAccount();
            account.setUserId(userId);
            account.setBalance(0L);
            account.setTotalRecharged(0L);
            account.setVersion(0);
            try {
                goldAccountMapper.insert(account);
            } catch (DuplicateKeyException ex) {
                log.debug("金砖账户已存在 userId={}", userId);
            }
        }
        if (memberAccountMapper.selectById(userId) == null) {
            MemberAccount member = new MemberAccount();
            member.setUserId(userId);
            member.setLevel(MemberAccount.LEVEL_NORMAL);
            member.setIsPermanent(0);
            try {
                memberAccountMapper.insert(member);
            } catch (DuplicateKeyException ex) {
                log.debug("会员账户已存在 userId={}", userId);
            }
        }
    }

    public User findByOpenid(String openid) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getOpenid, openid)
                .last("LIMIT 1"));
    }

    /**
     * 按手机号哈希查用户。手机号不落明文，只能通过 SHA-256 等值检索（01 §17）。
     *
     * @param phoneHash {@link CryptoUtil#sha256(String)} 计算出的哈希
     * @return 用户实体，不存在返回 null
     */
    public User findByPhoneHash(String phoneHash) {
        if (phoneHash == null || phoneHash.isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, phoneHash)
                .last("LIMIT 1"));
    }

    /**
     * 按 ID 取用户，不存在抛 40401。
     *
     * <p><b>调用方必须自行校验归属</b>：本方法只负责取数，不做权限判断。
     * 所有按 ID 取资源的接口都要防 IDOR（01 §2、§17）。</p>
     */
    public User requireById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "用户不存在，请重新登录");
        }
        return user;
    }

    /** 校验账号未被封禁。封禁态返回 40302（04 §5「账号受限」）。 */
    public void requireNotFrozen(User user) {
        if (user.isFrozen()) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "账号已被限制使用，请联系客服");
        }
    }

    /**
     * 装配用户资料 VO。
     *
     * <p>{@code goldBalance} 仅客户侧展示（04 §2.1）；打手侧传 {@code includeGold=false}，
     * 避免把客户余额暴露给打手身份。</p>
     *
     * @param userId      用户 ID
     * @param includeGold 是否装配金砖余额（客户 true / 打手 false）
     */
    public UserProfileVO buildProfile(Long userId, boolean includeGold) {
        User user = requireById(userId);

        String memberLevel = MemberAccount.LEVEL_NORMAL;
        MemberAccount member = memberAccountMapper.selectById(userId);
        if (member != null) {
            memberLevel = member.getLevelOrDefault();
        }

        long goldBalance = 0L;
        if (includeGold) {
            CustomerGoldAccount account = goldAccountMapper.selectById(userId);
            goldBalance = account == null ? 0L : account.getBalanceOrZero();
        }

        String phoneMasked = null;
        if (user.getPhoneCipher() != null) {
            phoneMasked = MaskUtil.maskPhone(cryptoUtil.decrypt(user.getPhoneCipher()));
        }

        return UserProfileVO.builder()
                .id(user.getId())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .phoneMasked(phoneMasked)
                .verified(user.hasVerified())
                .memberLevel(memberLevel)
                .goldBalance(includeGold ? goldBalance : 0L)
                .build();
    }

    /** 更新昵称/头像。对应 04 §3.1 {@code PUT /users/me/profile}。 */
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO updateProfile(Long userId, String nickname, String avatar) {
        User user = requireById(userId);
        User update = new User();
        update.setId(user.getId());
        if (nickname != null && !nickname.isBlank()) {
            if (nickname.length() > 64) {
                throw BizException.of(ErrorCode.PARAM_ERROR, "昵称不能超过 64 个字符，请修改后重试");
            }
            update.setNickname(nickname.trim());
        }
        if (avatar != null && !avatar.isBlank()) {
            if (avatar.length() > 512) {
                throw BizException.of(ErrorCode.PARAM_ERROR, "头像地址过长，请重新上传");
            }
            update.setAvatar(avatar.trim());
        }
        userMapper.updateById(update);
        return buildProfile(userId, true);
    }

    /**
     * 提交实名认证。对应 04 §3.1 {@code POST /users/me/verify}。
     *
     * <p><b>本期不接三方</b>（09 §1）：直接置 PASSED 并回写 {@code user.verified=1}，
     * 让下单/入驻的实名 guard 能按真实数据流转。上线前把这里换成二要素 + 人脸校验。</p>
     *
     * <p>{@code uk_user} 唯一：重复提交走 update，不产生第二条记录。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public VerifyStatusVO submitVerify(Long userId, String realName, String idCard, String faceToken) {
        if (realName == null || realName.isBlank() || idCard == null || idCard.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写真实姓名和身份证号");
        }
        if (!idCard.matches("^\\d{17}[\\dXx]$")) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "身份证号格式有误，请检查后重新填写");
        }

        String idCardHash = cryptoUtil.sha256(idCard.toUpperCase());
        String realNameCipher = cryptoUtil.encrypt(realName.trim());
        String idCardCipher = cryptoUtil.encrypt(idCard.toUpperCase());
        int age = calcAgeFromIdCard(idCard);

        UserVerify existing = userVerifyMapper.selectOne(new LambdaQueryWrapper<UserVerify>()
                .eq(UserVerify::getUserId, userId)
                .last("LIMIT 1"));

        // 同证不同人：05 §2.2 idx_id_card_hash 用于查重
        UserVerify sameCard = userVerifyMapper.selectOne(new LambdaQueryWrapper<UserVerify>()
                .eq(UserVerify::getIdCardHash, idCardHash)
                .last("LIMIT 1"));
        if (sameCard != null && !sameCard.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "该身份证已被其他账号实名，请联系客服处理");
        }

        UserVerify verify = existing == null ? new UserVerify() : existing;
        if (existing == null) {
            verify.setId(idGenerator.nextId());
            verify.setUserId(userId);
        }
        verify.setRealNameCipher(realNameCipher);
        verify.setIdCardCipher(idCardCipher);
        verify.setIdCardHash(idCardHash);
        verify.setAge(age);
        verify.setFaceResult(faceToken == null || faceToken.isBlank() ? "SKIP" : "PASS");

        // dev/本期：直接通过。生产接入三方后，此处应根据校验结果置 PENDING/REJECTED
        verify.setStatus(UserVerify.STATUS_PASSED);
        verify.setVerifiedAt(java.time.LocalDateTime.now());

        if (existing == null) {
            userVerifyMapper.insert(verify);
        } else {
            userVerifyMapper.updateById(verify);
        }

        User userUpdate = new User();
        userUpdate.setId(userId);
        userUpdate.setVerified(1);
        userMapper.updateById(userUpdate);

        log.info("实名认证通过 userId={} name={} age={}", userId, MaskUtil.maskRealName(realName), age);
        return toVerifyStatusVO(verify);
    }

    /** 查询实名状态。对应 04 §3.1 {@code GET /users/me/verify}。 */
    public VerifyStatusVO getVerifyStatus(Long userId) {
        UserVerify verify = userVerifyMapper.selectOne(new LambdaQueryWrapper<UserVerify>()
                .eq(UserVerify::getUserId, userId)
                .last("LIMIT 1"));
        if (verify == null) {
            return new VerifyStatusVO(UserVerify.STATUS_NONE, null, null);
        }
        return toVerifyStatusVO(verify);
    }

    /** 是否已实名。feature.realname=false 时调用方直接放行，不查本方法。 */
    public boolean isVerified(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.hasVerified();
    }

    private VerifyStatusVO toVerifyStatusVO(UserVerify verify) {
        return new VerifyStatusVO(verify.getStatus(), null, verify.getVerifiedAt());
    }

    /**
     * 从身份证第 7-14 位算年龄。
     *
     * <p>返回 {@link Integer} 而非 int：解析失败时为 null，对应
     * {@code user_verify.age} 允许 NULL（05 §2.2）。</p>
     */
    private static Integer calcAgeFromIdCard(String idCard) {
        try {
            int year = Integer.parseInt(idCard.substring(6, 10));
            int month = Integer.parseInt(idCard.substring(10, 12));
            int day = Integer.parseInt(idCard.substring(12, 14));
            java.time.LocalDate birth = java.time.LocalDate.of(year, month, day);
            return java.time.Period.between(birth, java.time.LocalDate.now()).getYears();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String defaultNickname(Long userId) {
        return "玩家" + (userId % 100000);
    }

    /**
     * 实名状态 VO。对应 04 §2.1 {@code interface VerifyStatus}。
     *
     * @param status     NONE / PENDING / PASSED / REJECTED
     * @param reason     驳回原因，仅 REJECTED 时有值
     * @param verifiedAt 通过时间
     */
    public record VerifyStatusVO(String status, String reason, java.time.LocalDateTime verifiedAt) {
    }
}
