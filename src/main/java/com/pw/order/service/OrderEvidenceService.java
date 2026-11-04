package com.pw.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.common.enums.OrderStatus;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.file.service.FileStorageService;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.OrderEvidence;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.OrderEvidenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 订单履约凭证服务。写入 {@code order_evidence}（05 §4.7）。
 *
 * <p>口径来源：01 §3 guard（开始服务需开局照、申请完单需开局照+结束照）、04 §3.6。</p>
 *
 * <p><b>类型</b>：{@code START}（开局照）、{@code END}（结束照）、{@code SUPPLEMENT}（补充）。
 * START/END 每单各一张即可，重复上传按补充处理；SUPPLEMENT 可多张。</p>
 *
 * <p><b>hash</b>：对 objectKey 取 SHA-256（05 §4.7 用 char(64)），
 * 便于检测同一文件重复提交；dev 阶段文件本体走本地目录，未接对象存储。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEvidenceService {

    /** 允许的凭证类型。 */
    private static final Set<String> VALID_TYPES = Set.of("START", "END", "SUPPLEMENT");

    private final OrderEvidenceMapper evidenceMapper;
    private final BizOrderMapper orderMapper;
    private final FileStorageService fileStorage;
    private final IdGenerator idGenerator;

    /**
     * 打手上传履约凭证。
     *
     * <p>guard：订单归属该打手、订单处于 ACCEPTED/IN_SERVICE/PENDING_CONFIRM
     * （终态或待接单不可补传）。</p>
     *
     * @param orderId    订单 ID
     * @param boosterId  打手 ID（从登录态取）
     * @param type       START / END / SUPPLEMENT
     * @param objectKey  文件标识
     * @param takenAt    拍摄时间，null 时用当前时间
     * @return 凭证记录
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderEvidence upload(Long orderId, Long boosterId, String type,
                                String objectKey, LocalDateTime takenAt) {
        if (type == null || !VALID_TYPES.contains(type)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "凭证类型只能是 START/END/SUPPLEMENT");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请先上传文件再提交凭证");
        }
        String key = objectKey.trim();
        // 只接受真实存在于存储中的图片：防止客户端随手编一个 objectKey 当凭证
        if (!fileStorage.exists(key)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "凭证图片不存在或已过期，请重新上传");
        }

        BizOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        if (!boosterId.equals(order.getBoosterId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能为自己的订单上传凭证");
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status == OrderStatus.PENDING_ACCEPT || status.isTerminal()) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "当前订单状态「" + status.label() + "」不可上传凭证");
        }

        // START/END 已存在时，本次按补充处理，避免覆盖首张关键凭证
        boolean supplement = false;
        if (!"SUPPLEMENT".equals(type)) {
            OrderEvidence existing = evidenceMapper.selectByOrderAndType(orderId, type);
            if (existing != null) {
                supplement = true;
                log.info("凭证已存在，按补充上传处理 orderId={} type={}", orderId, type);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        OrderEvidence evidence = new OrderEvidence();
        evidence.setId(idGenerator.nextId());
        evidence.setOrderId(orderId);
        evidence.setType(type);
        evidence.setObjectKey(key);
        evidence.setHash(fileStorage.hashOf(key));
        evidence.setSize((int) Math.min(fileStorage.sizeOf(key), Integer.MAX_VALUE));
        evidence.setTakenAt(takenAt == null ? now : takenAt);
        evidence.setUploaderId(boosterId);
        evidence.setSupplement(supplement ? 1 : 0);
        evidence.setCreatedAt(now);
        evidenceMapper.insert(evidence);

        log.info("凭证上传成功 orderId={} type={} evidenceId={} supplement={}",
                orderId, type, evidence.getId(), supplement);
        return evidence;
    }

    /** 按订单查全部凭证（时间升序）。 */
    public List<OrderEvidence> listByOrderId(Long orderId) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<OrderEvidence>()
                .eq(OrderEvidence::getOrderId, orderId)
                .orderByAsc(OrderEvidence::getCreatedAt)
                .orderByAsc(OrderEvidence::getId));
    }

    /** 按 ID 取凭证，校验归属订单。 */
    public OrderEvidence requireOfOrder(Long orderId, Long evidenceId) {
        if (evidenceId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请提供凭证 ID");
        }
        OrderEvidence evidence = evidenceMapper.selectById(evidenceId);
        if (evidence == null || !orderId.equals(evidence.getOrderId())) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "凭证不存在或不属于本订单");
        }
        return evidence;
    }
}
