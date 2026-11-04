package com.pw.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.PageResult;
import com.pw.common.util.IdGenerator;
import com.pw.im.dto.ImDto;
import com.pw.im.entity.ChatMessage;
import com.pw.im.entity.Conversation;
import com.pw.im.entity.SiteMessage;
import com.pw.im.mapper.ChatMessageMapper;
import com.pw.im.mapper.ConversationMapper;
import com.pw.im.mapper.SiteMessageMapper;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 客户 ↔ 客服 聊天 + 站内通知（v1 轮询模式，无 WebSocket）。
 *
 * <p>会话模型：一个客户一条会话（uk_customer），客服为共享队列，任何客服/管理员可回复。
 * 未读口径：客服发送 → 客户未读 +1；客户发送 → 客服端未读 +1；各自拉取消息时清零。</p>
 */
@Service
@RequiredArgsConstructor
public class ImService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SiteMessageMapper siteMessageMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;

    private static final int PREVIEW_LEN = 60;

    /* ─────────────────────── 客户端：会话 ─────────────────────── */

    /** 我的会话（不存在则懒创建，首次进入消息页调用）。 */
    @Transactional(rollbackFor = Exception.class)
    public ImDto.MyConversationView myConversation(Long customerId) {
        Conversation conversation = requireConversationOf(customerId);
        return toMyView(conversation);
    }

    /** 消息汇总：tab 角标 = 会话未读 + 通知未读。 */
    public ImDto.SummaryView summary(Long customerId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getCustomerId, customerId));
        int chatUnread = conversation == null ? 0 : conversation.getCustomerUnread();
        Long notifyUnread = siteMessageMapper.selectCount(new LambdaQueryWrapper<SiteMessage>()
                .eq(SiteMessage::getUserId, customerId)
                .isNull(SiteMessage::getReadAt));
        int notifyUnreadInt = notifyUnread == null ? 0 : Math.toIntExact(notifyUnread);
        return ImDto.SummaryView.builder()
                .conversationId(conversation == null ? null : conversation.getId())
                .chatUnread(chatUnread)
                .notifyUnread(notifyUnreadInt)
                .total(chatUnread + notifyUnreadInt)
                .build();
    }

    /** 会话历史（最新在前，分页），客户拉取即清零自身未读。 */
    @Transactional(rollbackFor = Exception.class)
    public PageResult<ImDto.MessageView> messages(Long conversationId, Long customerId, int page, int pageSize) {
        Conversation conversation = requireConversationOf(customerId);
        if (!conversation.getId().equals(conversationId)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在");
        }
        IPage<ChatMessage> result = chatMessageMapper.selectPage(
                Page.of(Math.max(1, page), Math.max(1, Math.min(pageSize, 50))),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getId));
        if (conversation.getCustomerUnread() > 0) {
            conversation.setCustomerUnread(0);
            conversationMapper.updateById(conversation);
        }
        return PageResult.from(result, this::toMessageView);
    }

    /** 客户发送消息（客服端未读 +1）。 */
    @Transactional(rollbackFor = Exception.class)
    public ImDto.MessageView sendFromCustomer(Long conversationId, Long customerId, String content) {
        Conversation conversation = requireConversationOf(customerId);
        if (!conversation.getId().equals(conversationId)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在");
        }
        User user = userMapper.selectById(customerId);
        String nickname = user == null ? null : user.getNickname();
        return insertMessage(conversation, Conversation.SENDER_CUSTOMER, customerId, nickname, content);
    }

    /* ─────────────────────── 客服端（共享） ─────────────────────── */

    /** 客服端拉取历史（最新在前，分页），拉取即清零客服端未读。 */
    @Transactional(rollbackFor = Exception.class)
    public PageResult<ImDto.MessageView> messagesForStaff(Long conversationId, int page, int pageSize) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在");
        }
        IPage<ChatMessage> result = chatMessageMapper.selectPage(
                Page.of(Math.max(1, page), Math.max(1, Math.min(pageSize, 50))),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getId));
        if (conversation.getStaffUnread() > 0) {
            conversation.setStaffUnread(0);
            conversationMapper.updateById(conversation);
        }
        return PageResult.from(result, this::toMessageView);
    }

    /** 客服/管理员回复（客户未读 +1）。 */
    @Transactional(rollbackFor = Exception.class)
    public ImDto.MessageView sendFromStaff(Long conversationId, Long staffId, String staffName, String content) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return insertMessage(conversation, Conversation.SENDER_STAFF, staffId, staffName, content);
    }

    /* ─────────────────────── 站内通知 ─────────────────────── */

    /** 通知列表（最新在前，分页）。 */
    public PageResult<ImDto.NotifyRow> notifications(Long userId, int page, int pageSize) {
        IPage<SiteMessage> result = siteMessageMapper.selectPage(
                Page.of(Math.max(1, page), Math.max(1, Math.min(pageSize, 50))),
                new LambdaQueryWrapper<SiteMessage>()
                        .eq(SiteMessage::getUserId, userId)
                        .orderByDesc(SiteMessage::getId));
        return PageResult.from(result, m -> ImDto.NotifyRow.builder()
                .id(m.getId())
                .type(m.getType())
                .title(m.getTitle())
                .content(m.getContent())
                .bizType(m.getBizType())
                .bizId(m.getBizId())
                .read(m.getReadAt() != null)
                .createdAt(m.getCreatedAt())
                .build());
    }

    /** 单条已读。 */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long messageId) {
        SiteMessage message = siteMessageMapper.selectById(messageId);
        if (message == null || !message.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "通知不存在");
        }
        if (message.getReadAt() == null) {
            message.setReadAt(java.time.LocalDateTime.now());
            siteMessageMapper.updateById(message);
        }
    }

    /** 全部已读，返回影响行数。 */
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long userId) {
        SiteMessage patch = new SiteMessage();
        patch.setReadAt(java.time.LocalDateTime.now());
        return siteMessageMapper.update(patch, new LambdaQueryWrapper<SiteMessage>()
                .eq(SiteMessage::getUserId, userId)
                .isNull(SiteMessage::getReadAt));
    }

    /**
     * 业务事件通知写入点（订单/退款/仲裁等关键节点调用）。
     * 与业务同事务：业务成功才落通知；通知失败也应让调用方感知（数据一致优先）。
     */
    public void notify(Long userId, String type, String title, String content, String bizType, Long bizId) {
        SiteMessage message = new SiteMessage();
        message.setId(idGenerator.nextId());
        message.setUserId(userId);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setBizType(bizType);
        message.setBizId(bizId);
        siteMessageMapper.insert(message);
    }

    /* ─────────────────────── 内部 ─────────────────────── */

    private Conversation requireConversationOf(Long customerId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getCustomerId, customerId));
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setId(idGenerator.nextId());
            conversation.setCustomerId(customerId);
            conversation.setCustomerUnread(0);
            conversation.setStaffUnread(0);
            conversationMapper.insert(conversation);
        }
        return conversation;
    }

    private ImDto.MessageView insertMessage(Conversation conversation, String senderType,
                                            Long senderId, String senderName, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "消息内容不能为空");
        }
        if (trimmed.length() > 2000) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "消息不能超过 2000 字");
        }
        ChatMessage message = new ChatMessage();
        message.setId(idGenerator.nextId());
        message.setConversationId(conversation.getId());
        message.setSenderType(senderType);
        message.setSenderId(senderId);
        message.setSenderName(senderName);
        message.setContent(trimmed);
        chatMessageMapper.insert(message);

        conversation.setLastMessagePreview(trimmed.length() > PREVIEW_LEN
                ? trimmed.substring(0, PREVIEW_LEN) : trimmed);
        conversation.setLastMessageAt(message.getCreatedAt());
        if (Conversation.SENDER_CUSTOMER.equals(senderType)) {
            conversation.setStaffUnread(conversation.getStaffUnread() + 1);
        } else {
            conversation.setCustomerUnread(conversation.getCustomerUnread() + 1);
        }
        conversationMapper.updateById(conversation);
        return toMessageView(message);
    }

    private ImDto.MessageView toMessageView(ChatMessage message) {
        return ImDto.MessageView.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .senderType(message.getSenderType())
                .senderName(message.getSenderName())
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private ImDto.MyConversationView toMyView(Conversation conversation) {
        return ImDto.MyConversationView.builder()
                .id(conversation.getId())
                .unread(conversation.getCustomerUnread())
                .lastMessagePreview(conversation.getLastMessagePreview())
                .lastMessageAt(conversation.getLastMessageAt())
                .build();
    }

    /** 供管理端查询会话归属客户（模块间仅 Service 调用）。 */
    public Conversation requireConversation(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    public List<Conversation> conversationsOf(List<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return List.of();
        }
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .in(Conversation::getCustomerId, customerIds));
    }
}
