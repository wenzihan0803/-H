package com.pw.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.PageResult;
import com.pw.common.util.CryptoUtil;
import com.pw.im.dto.ImDto;
import com.pw.im.entity.Conversation;
import com.pw.im.mapper.ConversationMapper;
import com.pw.order.entity.BizOrder;
import com.pw.order.service.OrderQueryService;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 管理端客服工作台：会话列表 / 会话详情 / 回复。 */
@Service
@RequiredArgsConstructor
public class AdminImService {

    private final ImService imService;
    private final ConversationMapper conversationMapper;
    private final UserMapper userMapper;
    private final CryptoUtil cryptoUtil;
    private final OrderQueryService orderQueryService;

    /** 会话列表（最近消息在前，固定 10 条/页；支持昵称模糊 / 手机号精确搜索）。 */
    public PageResult<ImDto.AdminConversationRow> conversations(String keyword, int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 50));
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .orderByDesc(Conversation::getLastMessageAt)
                .orderByDesc(Conversation::getId);
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && !kw.isEmpty()) {
            List<Long> customerIds = searchUserIds(kw);
            if (customerIds.isEmpty()) {
                return PageResult.empty(Math.max(1, page), size);
            }
            wrapper.in(Conversation::getCustomerId, customerIds);
        }
        IPage<Conversation> result = conversationMapper.selectPage(
                Page.of(Math.max(1, page), size), wrapper);

        List<ImDto.AdminConversationRow> rows = new ArrayList<>(result.getRecords().size());
        for (Conversation conversation : result.getRecords()) {
            User user = userMapper.selectById(conversation.getCustomerId());
            rows.add(ImDto.AdminConversationRow.builder()
                    .id(conversation.getId())
                    .customerId(conversation.getCustomerId())
                    .nickname(user == null ? "用户" + conversation.getCustomerId() : user.getNickname())
                    .phone(user == null ? null : cryptoUtil.decrypt(user.getPhoneCipher()))
                    .lastMessagePreview(conversation.getLastMessagePreview())
                    .lastMessageAt(conversation.getLastMessageAt())
                    .staffUnread(conversation.getStaffUnread())
                    .build());
        }
        return PageResult.from(result, rows);
    }

    /** 会话详情（聊天框头部：客户身份 + 最近一单）。 */
    public ImDto.AdminConversationDetail detail(Long conversationId) {
        Conversation conversation = imService.requireConversation(conversationId);
        User user = userMapper.selectById(conversation.getCustomerId());
        ImDto.RecentOrder recentOrder = null;
        IPage<BizOrder> latest = orderQueryService.listByCustomer(conversation.getCustomerId(), null, 1, 1);
        if (latest != null && !latest.getRecords().isEmpty()) {
            BizOrder order = latest.getRecords().get(0);
            recentOrder = ImDto.RecentOrder.builder()
                    .id(order.getId())
                    .orderNo(order.getOrderNo())
                    .status(order.getStatus())
                    .payAmount(order.getPayAmount())
                    .createdAt(order.getCreatedAt())
                    .build();
        }
        return ImDto.AdminConversationDetail.builder()
                .id(conversation.getId())
                .customerId(conversation.getCustomerId())
                .nickname(user == null ? "用户" + conversation.getCustomerId() : user.getNickname())
                .phone(user == null ? null : cryptoUtil.decrypt(user.getPhoneCipher()))
                .staffUnread(conversation.getStaffUnread())
                .recentOrder(recentOrder)
                .build();
    }

    /** 客服端未读总数（侧栏角标）。 */
    public long unreadTotal() {
        QueryWrapper<Conversation> wrapper = new QueryWrapper<>();
        wrapper.select("COALESCE(SUM(staff_unread), 0) AS total");
        List<Map<String, Object>> rows = conversationMapper.selectMaps(wrapper);
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return 0;
        }
        Object value = rows.get(0).get("total");
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** 客服拉取历史（清零该会话客服端未读）。 */
    public PageResult<ImDto.MessageView> messages(Long conversationId, int page, int pageSize) {
        imService.requireConversation(conversationId);
        return imService.messagesForStaff(conversationId, page, pageSize);
    }

    /** 客服回复。 */
    public ImDto.MessageView reply(Long conversationId, Long staffId, String staffName, String content) {
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "回复内容不能为空");
        }
        imService.requireConversation(conversationId);
        return imService.sendFromStaff(conversationId, staffId, staffName, content.trim());
    }

    /* ─────────────────────── 内部 ─────────────────────── */

    /** 昵称模糊 / 手机号精确 → 客户 user_id 集合。 */
    private List<Long> searchUserIds(String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword.matches("1[3-9]\\d{9}")) {
            wrapper.eq(User::getPhoneHash, cryptoUtil.sha256(keyword));
        } else {
            wrapper.like(User::getNickname, keyword);
        }
        wrapper.select(User::getId);
        return userMapper.selectList(wrapper).stream().map(User::getId).toList();
    }
}
