package com.pw.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.order.dto.ComplaintDto;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.Complaint;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.ComplaintMapper;
import com.pw.refund.entity.Refund;
import com.pw.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.pw.im.entity.SiteMessage;
import com.pw.im.service.ImService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户仲裁。
 *
 * <p>规则（2026-10-31 确认）：</p>
 * <ul>
 *   <li>仅「服务中 / 待确认」可发起，钱仍在托管，未结算；</li>
 *   <li>发起后订单转 DISPUTING，自动确认暂停；</li>
 *   <li>管理员处理：0 = 驳回回原状态；1–100 = 退客户该比例，剩余全给打手，平台 0。</li>
 * </ul>
 *
 * <p>资金动作复用退款链路（{@link RefundService}），避免两套结算逻辑漂移。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbitrationService {

    private final ComplaintMapper complaintMapper;
    private final BizOrderMapper orderMapper;
    private final RefundService refundService;
    private final OrderProgressRecorder progressRecorder;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ImService imService;

    @Transactional(rollbackFor = Exception.class)
    public ComplaintDto.View create(Long orderId, Long customerId, String reason, List<String> evidenceKeys) {
        BizOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        if (!customerId.equals(order.getCustomerId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能对自己的订单申请仲裁");
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.IN_SERVICE && status != OrderStatus.PENDING_CONFIRM) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "当前状态不可申请仲裁（仅服务中/待确认）");
        }
        if (complaintMapper.selectActiveByOrderId(orderId) != null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该订单已有进行中的仲裁，请等待处理");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.transitStatus(orderId, status.name(), OrderStatus.DISPUTING.name(), now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        Complaint complaint = new Complaint();
        complaint.setId(idGenerator.nextId());
        complaint.setType("ARBITRATION");
        complaint.setPriority("NORMAL");
        complaint.setOrderId(orderId);
        complaint.setCustomerId(order.getCustomerId());
        complaint.setBoosterId(order.getBoosterId());
        complaint.setDescription(reason);
        complaint.setEvidenceJson(writeEvidence(evidenceKeys));
        complaint.setStatus(Complaint.STATUS_OPEN);
        complaint.setPreviousStatus(status.name());
        complaint.setCreatedAt(now);
        complaint.setUpdatedAt(now);
        complaintMapper.insert(complaint);

        progressRecorder.record(orderId, status, OrderStatus.DISPUTING, OrderAction.DISPUTE_SUBMIT,
                OrderProgressRecorder.OP_CUSTOMER, customerId, "客户申请仲裁：" + reason);
        log.info("客户申请仲裁 orderId={} complaintId={} customerId={}", orderId, complaint.getId(), customerId);
        return toView(complaint, order);
    }

    /** 客户查自己订单的仲裁单；没有返回 null。 */
    public ComplaintDto.View my(Long orderId, Long customerId) {
        Complaint complaint = complaintMapper.selectOne(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderId, orderId)
                .eq(Complaint::getCustomerId, customerId)
                .orderByDesc(Complaint::getCreatedAt)
                .last("LIMIT 1"));
        if (complaint == null) {
            return null;
        }
        return toView(complaint, orderMapper.selectById(orderId));
    }

    public List<ComplaintDto.View> list(String status) {
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .orderByDesc(Complaint::getCreatedAt);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Complaint::getStatus, status);
        }
        List<ComplaintDto.View> result = new ArrayList<>();
        for (Complaint complaint : complaintMapper.selectList(wrapper)) {
            result.add(toView(complaint, orderMapper.selectById(complaint.getOrderId())));
        }
        return result;
    }

    /**
     * 管理员处理仲裁。
     *
     * @param refundRate 0 = 驳回；1–100 = 退客户比例
     */
    @Transactional(rollbackFor = Exception.class)
    public ComplaintDto.View resolve(Long complaintId, Long operatorId, Role operatorRole,
                                     int refundRate, String note) {
        if (refundRate < 0 || refundRate > 100) {
            throw BizException.of(ErrorCode.REFUND_RATE_INVALID, "扣款比例只能是 0–100");
        }
        Complaint complaint = complaintMapper.selectForUpdate(complaintId);
        if (complaint == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "仲裁单不存在");
        }
        if (!Complaint.STATUS_OPEN.equals(complaint.getStatus())
                && !Complaint.STATUS_PROCESSING.equals(complaint.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该仲裁单已处理，请刷新列表");
        }
        BizOrder order = orderMapper.selectForUpdate(complaint.getOrderId());
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!OrderStatus.DISPUTING.name().equals(order.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单当前不在仲裁中，请刷新");
        }

        LocalDateTime now = LocalDateTime.now();
        if (refundRate == 0) {
            int rows = orderMapper.transitStatus(order.getId(), OrderStatus.DISPUTING.name(),
                    complaint.getPreviousStatus(), now);
            if (rows == 0) {
                throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新");
            }
            complaint.setStatus(Complaint.STATUS_CLOSED);
            complaint.setHandleNote(note);
            complaint.setOwnerId(operatorId);
            complaint.setResolvedAt(now);
            complaint.setUpdatedAt(now);
            complaintMapper.updateById(complaint);
            progressRecorder.recordNoTransition(order.getId(),
                    OrderStatus.of(complaint.getPreviousStatus()), OrderAction.DISPUTE_REJECT,
                    OrderProgressRecorder.OP_ADMIN, operatorId, "仲裁驳回，订单恢复原状态");
            log.info("仲裁驳回 complaintId={} orderId={}", complaintId, order.getId());

            imService.notify(complaint.getCustomerId(), SiteMessage.TYPE_ARBITRATION, "仲裁已处理",
                    "订单 " + order.getOrderNo() + " 的仲裁未支持退款，订单已恢复原状态",
                    "ARBITRATION", complaint.getId());
            return toView(complaint, order);
        }

        Refund refund = refundService.createArbitrationRefund(order, refundRate,
                complaint.getDescription(), complaint.getEvidenceJson(),
                complaint.getCustomerId(), complaint.getPreviousStatus());
        refundService.approveDisputeRefund(refund.getId(), operatorId, operatorRole);

        complaint.setStatus(Complaint.STATUS_RESOLVED);
        complaint.setHandleNote(note);
        complaint.setOwnerId(operatorId);
        complaint.setRefundId(refund.getId());
        complaint.setResolvedAt(now);
        complaint.setUpdatedAt(now);
        complaintMapper.updateById(complaint);
        log.info("仲裁通过 complaintId={} orderId={} rate={}% refundId={}",
                complaintId, order.getId(), refundRate, refund.getId());
        return toView(complaint, orderMapper.selectById(order.getId()));
    }

    private ComplaintDto.View toView(Complaint complaint, BizOrder order) {
        return ComplaintDto.View.builder()
                .id(complaint.getId())
                .orderId(complaint.getOrderId())
                .orderNo(order == null ? null : order.getOrderNo())
                .payAmount(order == null ? null : order.getPayAmount())
                .status(complaint.getStatus())
                .previousStatus(complaint.getPreviousStatus())
                .description(complaint.getDescription())
                .evidence(readEvidence(complaint.getEvidenceJson()))
                .handleNote(complaint.getHandleNote())
                .refundId(complaint.getRefundId())
                .resolvedAt(complaint.getResolvedAt())
                .createdAt(complaint.getCreatedAt())
                .build();
    }

    private String writeEvidence(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "凭证格式有误，请重试");
        }
    }

    private List<String> readEvidence(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
