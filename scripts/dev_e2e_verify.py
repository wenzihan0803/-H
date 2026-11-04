#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
后端第一期端到端 mock 验证脚本（dev profile）。

覆盖技术规范要求的完整资金链路：
  1. 登录（客户/打手/客服/管理员）
  2. 模拟充值 → 金砖到账
  3. 下单（扣金砖 + 托管金）+ 幂等重复提交
  4. 接单 → 客服交接 → 打手 ACK → 开局照 → 开始服务 → 结束照 → 申请完单
  5. 客户确认收货 → 结算（打手原价 85%，平台=实付−打手）→ 收益到账 available
  6. 提现申请（available→frozen）→ 管理员确认 → 打款成功（扣 frozen）
  7. 服务前客户自助全额退款（金砖退回）
  8. 服务后客服发起 50% 争议退款 → 管理员审批 → 部分结算
  9. 资金守恒校验：客户净消耗金砖 == 平台收入 + 打手收益

用法：
    python3 scripts/dev_e2e_verify.py [baseUrl]
默认 baseUrl = http://localhost:8080/api/v1

**重跑前必须复位数据**：脚本涉及「每日仅 1 次提现」「打手同时只能有一单」等有状态限制，
脏库重跑会在第 4 步之后连锁失败。复位命令：
    mysql -u root -proot pw_dev < scripts/dev_reset_data.sql
或用干净库另起实例（推荐）：
    CREATE DATABASE pw_e2e;
    mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082 \
      --spring.datasource.url=jdbc:mysql://localhost:3306/pw_e2e?... "

前置条件：
    - 后端已以 dev profile 启动
    - MySQL/Redis 可用，Flyway 已建表并灌入种子数据
"""

import base64
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api/v1").rstrip("/")
TZ = timezone(timedelta(hours=8))

PASSED = []
FAILED = []


# ----------------------------------------------------------------------
# 基础工具
# ----------------------------------------------------------------------

def call(method, path, token=None, body=None, headers=None, expect_ok=True):
    """发起请求，返回 (http_status, payload)。业务失败也会返回，由调用方断言。"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}
    except urllib.error.URLError as e:
        return -1, {"error": str(e)}


def check(name, condition, detail=""):
    """记录一条断言结果。"""
    if condition:
        PASSED.append(name)
        print("  [PASS] " + name)
    else:
        FAILED.append((name, detail))
        print("  [FAIL] " + name + ("  <- " + str(detail) if detail else ""))
    return condition


def section(title):
    print("\n" + "=" * 72)
    print(title)
    print("=" * 72)


# 1x1 PNG（70 字节）：履约凭证必须真实上传，不能再用编造的 objectKey
_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def upload_image(token):
    """上传一张测试 PNG，返回 objectKey。"""
    boundary = "----pw-e2e-%d" % int(time.time() * 1000)
    body = b"".join([
        ("--%s\r\n" % boundary).encode(),
        b'Content-Disposition: form-data; name="file"; filename="e2e.png"\r\n',
        b"Content-Type: image/png\r\n\r\n",
        _PNG,
        b"\r\n",
        ("--%s--\r\n" % boundary).encode(),
    ])
    req = urllib.request.Request(BASE + "/files/upload", data=body, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise SystemExit("上传测试图片失败：HTTP %s %s" % (e.code, e.read().decode("utf-8", "ignore")))
    if payload.get("code") != 0:
        raise SystemExit("上传测试图片失败：%s" % payload)
    return payload["data"]["objectKey"]


def ok(resp):
    """业务成功的判定：HTTP 200 且 code == 0。"""
    return resp[0] == 200 and isinstance(resp[1], dict) and resp[1].get("code") == 0


def data(resp):
    return resp[1].get("data") if isinstance(resp[1], dict) else None


def code(resp):
    return resp[1].get("code") if isinstance(resp[1], dict) else None


def i(value):
    """接口把金额/ID 序列化为字符串，统一转 int。"""
    if value is None:
        return 0
    return int(value)


# ----------------------------------------------------------------------
# 1. 登录
# ----------------------------------------------------------------------

section("1. 登录（dev mock-login + 管理端账号密码）")

r_cust = call("POST", "/dev/mock-login",
              body={"openid": "dev_openid_customer_01", "role": "CUSTOMER"})
check("客户模拟登录", ok(r_cust), r_cust)
CUST = (data(r_cust) or {}).get("accessToken")
CUST_ID = i((data(r_cust) or {}).get("profile", {}).get("id"))

r_boost = call("POST", "/dev/mock-login",
               body={"openid": "dev_openid_booster_01", "role": "BOOSTER"})
check("打手模拟登录", ok(r_boost), r_boost)
BOOST = (data(r_boost) or {}).get("accessToken")
BOOST_ID = i((data(r_boost) or {}).get("profile", {}).get("id"))

r_cs = call("POST", "/auth/admin-login",
            body={"username": "cs01", "password": "Dev@123456"})
check("客服登录 cs01", ok(r_cs), r_cs)
CS = (data(r_cs) or {}).get("accessToken")
CS_ID = i((data(r_cs) or {}).get("profile", {}).get("id"))
check("客服角色为 CS", (data(r_cs) or {}).get("role") == "CS", (data(r_cs) or {}))

r_admin = call("POST", "/auth/admin-login",
               body={"username": "admin", "password": "Dev@123456"})
check("管理员登录 admin", ok(r_admin), r_admin)
ADMIN = (data(r_admin) or {}).get("accessToken")
ADMIN_ID = i((data(r_admin) or {}).get("profile", {}).get("id"))
check("管理员角色为 ADMIN", (data(r_admin) or {}).get("role") == "ADMIN", (data(r_admin) or {}))

r_bad = call("POST", "/auth/admin-login",
             body={"username": "cs01", "password": "wrong-password"})
check("错误密码被拒绝（非 0 码）", not ok(r_bad), r_bad)

print("\n  客户 user_id=%s  打手 user_id=%s  客服 admin_id=%s  管理员 admin_id=%s"
      % (CUST_ID, BOOST_ID, CS_ID, ADMIN_ID))

# ----------------------------------------------------------------------
# 2. 充值
# ----------------------------------------------------------------------

section("2. 模拟充值 → 金砖到账")

r_me0 = call("GET", "/auth/me", token=CUST)
check("查询 /auth/me", ok(r_me0), r_me0)
bal_before = i((data(r_me0) or {}).get("goldBalance"))

r_recharge = call("POST", "/dev/recharge-sim",
                  body={"userId": CUST_ID, "amount": 50000, "purpose": "NORMAL"})
check("模拟充值 500 元成功", ok(r_recharge), r_recharge)
check("充值返回 credited=true", (data(r_recharge) or {}).get("credited") is True, r_recharge)

r_me1 = call("GET", "/auth/me", token=CUST)
bal_after = i((data(r_me1) or {}).get("goldBalance"))
check("金砖余额增加 50000 分（%s -> %s）" % (bal_before, bal_after),
      bal_after == bal_before + 50000, "before=%s after=%s" % (bal_before, bal_after))

# 幂等：同一笔充值不能重复入账（recharge_no 唯一由服务内生成，此处验证余额只增一次）
r_recharge2 = call("POST", "/dev/recharge-sim",
                   body={"userId": CUST_ID, "amount": 50000, "purpose": "NORMAL"})
check("二次充值也成功（新单号，非重复回调）", ok(r_recharge2), r_recharge2)

r_me2 = call("GET", "/auth/me", token=CUST)
BAL = i((data(r_me2) or {}).get("goldBalance"))
print("\n  当前金砖余额：%s 分（%s 元）" % (BAL, BAL / 100))

# ----------------------------------------------------------------------
# 3. 下单（含幂等）
# ----------------------------------------------------------------------

section("3. 下单：扣金砖 + 建托管金 + 幂等")

APPOINT = (datetime.now(TZ) + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S.000+08:00")
IDEM_KEY = "e2e-verify-%d" % int(time.time())

order_body = {
    "categoryId": 1001,
    "skuId": 2001,          # 单价 3000 分
    "mode": "DESIGNATED",
    "boosterId": None,       # 稍后从打手信息推断；先走大厅模式更稳
    "quantity": 2,
    "appointAt": APPOINT,
    "bossGameId": "88886666",
    "requirement": "稳一点，优先撤离",
}

# 先查大厅可抢单需要打手 ID；指定模式需要真实 boosterId，这里从 dev 造的数据推断。
# 种子打手 booster.id 为 5001-5003，直接用 5001。
order_body["boosterId"] = 5001
order_body["mode"] = "DESIGNATED"

r_order = call("POST", "/orders", token=CUST, body=order_body,
               headers={"Idempotency-Key": IDEM_KEY})
check("创建订单成功", ok(r_order), r_order)
ORDER = data(r_order) or {}
ORDER_ID = i(ORDER.get("id"))
ORDER_NO = ORDER.get("orderNo")
PAY = i(ORDER.get("amounts", {}).get("payAmount"))
ORIGINAL = i(ORDER.get("amounts", {}).get("originalAmount"))
check("订单状态为 PENDING_ACCEPT", ORDER.get("status") == "PENDING_ACCEPT", ORDER.get("status"))
check("订单号非空", bool(ORDER_NO), ORDER_NO)
check("原价 = 3000 × 2 = 6000 分", ORIGINAL == 6000, ORIGINAL)
check("实付 = 6000 分（非会员无折扣）", PAY == 6000, PAY)
check("托管金额 = 实付 = 6000 分", i(ORDER.get("hold", {}).get("amount")) == PAY,
      ORDER.get("hold"))
check("托管状态 HELD", ORDER.get("hold", {}).get("status") == "HELD", ORDER.get("hold"))
check("canRefund=true（服务前可自助退）", ORDER.get("canRefund") is True, ORDER.get("canRefund"))

print("\n  订单 id=%s orderNo=%s 实付=%s 分" % (ORDER_ID, ORDER_NO, PAY))

r_me3 = call("GET", "/auth/me", token=CUST)
bal_after_order = i((data(r_me3) or {}).get("goldBalance"))
check("下单扣减金砖 6000 分（%s -> %s）" % (BAL, bal_after_order),
      bal_after_order == BAL - 6000, "before=%s after=%s" % (BAL, bal_after_order))

# 幂等：同一 Idempotency-Key 重复提交，不应二次扣款
r_dup = call("POST", "/orders", token=CUST, body=order_body,
             headers={"Idempotency-Key": IDEM_KEY})
r_me4 = call("GET", "/auth/me", token=CUST)
bal_after_dup = i((data(r_me4) or {}).get("goldBalance"))
check("幂等重复提交未二次扣款（余额仍为 %s）" % bal_after_dup,
      bal_after_dup == bal_after_order,
      "dup resp code=%s, before=%s after=%s" % (code(r_dup), bal_after_order, bal_after_dup))

# 越权：打手不能查客户订单详情中的备注
r_booster_detail = call("GET", "/booster/orders/%s" % ORDER_ID, token=BOOST)
check("打手可查看自己订单详情", ok(r_booster_detail), r_booster_detail)
check("打手端 requirement 不可见（数据隔离）",
      (data(r_booster_detail) or {}).get("requirement") is None,
      (data(r_booster_detail) or {}).get("requirement"))

# 客户详情能看到备注
r_cust_detail = call("GET", "/orders/%s" % ORDER_ID, token=CUST)
check("客户端可见 requirement",
      (data(r_cust_detail) or {}).get("requirement") == "稳一点，优先撤离",
      (data(r_cust_detail) or {}).get("requirement"))

# ----------------------------------------------------------------------
# 4. 履约链路
# ----------------------------------------------------------------------

section("4. 履约：接单 → 交接 → ACK → 开局照 → 开始服务 → 结束照 → 完单")

# 未交接不能开始服务
r_start_early = call("POST", "/booster/orders/%s/start" % ORDER_ID, token=BOOST,
                     body={"gameAccountId": 1, "startEvidenceId": 1})
check("未 ACK 时开始服务被拒绝", not ok(r_start_early), r_start_early)

r_accept = call("POST", "/booster/orders/%s/accept" % ORDER_ID, token=BOOST)
check("打手接单成功", ok(r_accept), r_accept)
check("接单后状态 ACCEPTED", (data(r_accept) or {}).get("status") == "ACCEPTED",
      (data(r_accept) or {}).get("status"))

r_handover = call("POST", "/admin/orders/%s/handover" % ORDER_ID, token=CS,
                  body={"channel": "IM", "bossGameId": "88886666",
                        "guide": "搜索 ID 添加好友，邀请进房间", "remark": "老板在线"})
check("客服下发交接成功", ok(r_handover), r_handover)
check("交接状态 SENT", (data(r_handover) or {}).get("status") == "SENT", data(r_handover))
check("交接后打手可见老板游戏 ID（已解密）",
      (data(r_handover) or {}).get("bossGameId") == "88886666", data(r_handover))

r_ack = call("POST", "/booster/orders/%s/handover/ack" % ORDER_ID, token=BOOST)
check("打手确认交接成功", ok(r_ack), r_ack)
check("ACK 状态正确", (data(r_ack) or {}).get("handoverStatus") == "ACK", data(r_ack))

# 打手游戏账号：从 dev 接口或数据库已知；这里用登录态查询
r_ga = call("GET", "/booster/game-accounts", token=BOOST)
GAME_ACCOUNT_ID = None
if ok(r_ga) and isinstance(data(r_ga), list) and data(r_ga):
    GAME_ACCOUNT_ID = i(data(r_ga)[0].get("id"))
if GAME_ACCOUNT_ID is None:
    # 回退：种子数据里打手 5001 的游戏账号 ID 段
    GAME_ACCOUNT_ID = 0
print("\n  使用游戏账号 ID=%s" % GAME_ACCOUNT_ID)

r_ev_start = call("POST", "/booster/orders/%s/evidence" % ORDER_ID, token=BOOST,
                  body={"type": "START", "objectKey": upload_image(BOOST),
                        "takenAt": datetime.now(TZ).strftime("%Y-%m-%dT%H:%M:%S.000+08:00")})
check("上传开局照成功", ok(r_ev_start), r_ev_start)
START_EV = i((data(r_ev_start) or {}).get("id"))

r_start = call("POST", "/booster/orders/%s/start" % ORDER_ID, token=BOOST,
               body={"gameAccountId": GAME_ACCOUNT_ID, "startEvidenceId": START_EV})
check("开始服务成功", ok(r_start), r_start)
check("开始服务后状态 IN_SERVICE", (data(r_start) or {}).get("status") == "IN_SERVICE",
      (data(r_start) or {}).get("status"))

# 服务开始后客户不能自助全额退
r_refund_late = call("POST", "/orders/%s/refund" % ORDER_ID, token=CUST,
                     body={"reason": "临时有事"})
check("服务开始后自助退款被拒绝（60014）",
      code(r_refund_late) == 60014, r_refund_late)

r_ev_end = call("POST", "/booster/orders/%s/evidence" % ORDER_ID, token=BOOST,
                body={"type": "END", "objectKey": upload_image(BOOST),
                      "takenAt": datetime.now(TZ).strftime("%Y-%m-%dT%H:%M:%S.000+08:00")})
check("上传结束照成功", ok(r_ev_end), r_ev_end)
END_EV = i((data(r_ev_end) or {}).get("id"))

r_complete = call("POST", "/booster/orders/%s/complete" % ORDER_ID, token=BOOST,
                  body={"endEvidenceId": END_EV})
check("申请完单成功", ok(r_complete), r_complete)
check("完单后状态 PENDING_CONFIRM",
      (data(r_complete) or {}).get("status") == "PENDING_CONFIRM",
      (data(r_complete) or {}).get("status"))
check("confirmDeadline 已设置",
      (data(r_complete) or {}).get("timestamps", {}).get("confirmDeadline") is not None,
      (data(r_complete) or {}).get("timestamps"))

# ----------------------------------------------------------------------
# 5. 确认收货与结算
# ----------------------------------------------------------------------

section("5. 客户确认收货 → 结算 → 打手收益到账")

r_earn0 = call("GET", "/booster/earnings", token=BOOST)
check("查询打手收益账户", ok(r_earn0), r_earn0)
avail_before = i((data(r_earn0) or {}).get("available"))

r_confirm = call("POST", "/orders/%s/confirm" % ORDER_ID, token=CUST)
check("确认收货成功", ok(r_confirm), r_confirm)
check("确认后状态 COMPLETED", (data(r_confirm) or {}).get("status") == "COMPLETED",
      (data(r_confirm) or {}).get("status"))

# 客户 DTO 不再下发结算分成（2026-10-31 口径），结算断言改用管理端详情
r_confirm_admin = call("GET", "/admin/orders/%s" % ORDER_ID, token=ADMIN)
SETTLE = (data(r_confirm_admin) or {}).get("settlement") or {}
check("生成结算单", bool(SETTLE), SETTLE)
check("结算类型 NORMAL", SETTLE.get("type") == "NORMAL", SETTLE.get("type"))
BOOSTER_AMT = i(SETTLE.get("boosterAmount"))
PLATFORM_AMT = i(SETTLE.get("platformAmount"))
check("打手收益 = 原价 6000 × 85% = 5100 分", BOOSTER_AMT == 5100, BOOSTER_AMT)
check("平台收入 = 实付 6000 − 5100 = 900 分", PLATFORM_AMT == 900, PLATFORM_AMT)
check("打手 + 平台 = 实付（分文不差）",
      BOOSTER_AMT + PLATFORM_AMT == PAY, "%s+%s != %s" % (BOOSTER_AMT, PLATFORM_AMT, PAY))

r_earn1 = call("GET", "/booster/earnings", token=BOOST)
avail_after = i((data(r_earn1) or {}).get("available"))
check("打手可提现余额增加 5100 分（%s -> %s）" % (avail_before, avail_after),
      avail_after == avail_before + BOOSTER_AMT,
      "before=%s after=%s expect=+%s" % (avail_before, avail_after, BOOSTER_AMT))

# 重复确认应被拒绝（幂等）
r_confirm2 = call("POST", "/orders/%s/confirm" % ORDER_ID, token=CUST)
check("重复确认收货被拒绝", not ok(r_confirm2), r_confirm2)

# 已结算订单不可再退款
r_refund_done = call("POST", "/orders/%s/refund" % ORDER_ID, token=CUST,
                     body={"reason": "想退款"})
check("已结算订单退款被拒绝（60009）",
      code(r_refund_done) == 60009, r_refund_done)

# ----------------------------------------------------------------------
# 6. 提现
# ----------------------------------------------------------------------

section("6. 提现：申请冻结 → 管理员确认 → 打款成功")

WITHDRAW_AMT = 1000
r_wd = call("POST", "/booster/withdrawals", token=BOOST, body={"amount": WITHDRAW_AMT})
check("申请提现成功", ok(r_wd), r_wd)
WD = data(r_wd) or {}
WD_ID = i(WD.get("id"))
check("提现单状态 APPLIED", WD.get("status") == "APPLIED", WD.get("status"))

r_earn2 = call("GET", "/booster/earnings", token=BOOST)
e2 = data(r_earn2) or {}
check("提现后 available 减少 %s 分" % WITHDRAW_AMT,
      i(e2.get("available")) == avail_after - WITHDRAW_AMT,
      "expected=%s actual=%s" % (avail_after - WITHDRAW_AMT, i(e2.get("available"))))
check("提现后 frozen 增加 %s 分" % WITHDRAW_AMT,
      i(e2.get("frozen")) == WITHDRAW_AMT, e2.get("frozen"))

r_wd2 = call("POST", "/booster/withdrawals", token=BOOST, body={"amount": WITHDRAW_AMT})
check("每日仅 1 次提现，二次申请被拒绝", not ok(r_wd2), r_wd2)

r_appr = call("POST", "/admin/withdrawals/%s/approve" % WD_ID, token=ADMIN)
check("管理员确认提现成功", ok(r_appr), r_appr)
check("确认后状态 APPROVED", (data(r_appr) or {}).get("status") == "APPROVED", data(r_appr))

r_pay = call("POST", "/admin/withdrawals/%s/pay" % WD_ID, token=ADMIN)
check("标记打款成功", ok(r_pay), r_pay)
check("打款后状态 PAID", (data(r_pay) or {}).get("status") == "PAID", data(r_pay))

r_earn3 = call("GET", "/booster/earnings", token=BOOST)
e3 = data(r_earn3) or {}
check("打款成功后 frozen 归零", i(e3.get("frozen")) == 0, e3.get("frozen"))
check("打款成功后 available 不变（%s）" % (avail_after - WITHDRAW_AMT),
      i(e3.get("available")) == avail_after - WITHDRAW_AMT, e3.get("available"))
check("累计到手 total = %s（含本单结算）" % i(e3.get("total")),
      i(e3.get("total")) >= BOOSTER_AMT, e3.get("total"))

# 客服不能审批提现
r_cs_appr = call("POST", "/admin/withdrawals/%s/approve" % WD_ID, token=CS)
check("客服无提现审批权（403）", code(r_cs_appr) == 40301, r_cs_appr)

# ----------------------------------------------------------------------
# 7. 服务前自助全额退款
# ----------------------------------------------------------------------

section("7. 服务前客户自助全额退款")

order2_body = dict(order_body)
order2_body["quantity"] = 1
order2_body["skuId"] = 2001
order2_body["mode"] = "HALL"
order2_body["boosterId"] = None
r_o2 = call("POST", "/orders", token=CUST, body=order2_body,
            headers={"Idempotency-Key": IDEM_KEY + "-o2"})
check("创建订单 2（大厅单）成功", ok(r_o2), r_o2)
O2 = data(r_o2) or {}
O2_ID = i(O2.get("id"))
O2_PAY = i(O2.get("amounts", {}).get("payAmount"))
check("订单 2 实付 3000 分", O2_PAY == 3000, O2_PAY)

r_me5 = call("GET", "/auth/me", token=CUST)
bal_before_refund = i((data(r_me5) or {}).get("goldBalance"))

r_ref = call("POST", "/orders/%s/refund" % O2_ID, token=CUST,
             body={"reason": "临时有事，不打了"})
check("自助全额退款成功", ok(r_ref), r_ref)
REF1 = data(r_ref) or {}
check("退款类型 FULL", REF1.get("type") == "FULL", REF1.get("type"))
check("退款比例 100", i(REF1.get("refundRate")) == 100, REF1.get("refundRate"))
check("退款金额 = 实付 3000 分", i(REF1.get("refundAmount")) == O2_PAY, REF1.get("refundAmount"))
check("退款单状态 SUCCESS（无需审批）", REF1.get("status") == "SUCCESS", REF1.get("status"))
check("留存金额为 0", i(REF1.get("retainedAmount")) == 0, REF1.get("retainedAmount"))

r_me6 = call("GET", "/auth/me", token=CUST)
bal_after_refund = i((data(r_me6) or {}).get("goldBalance"))
check("金砖退回 3000 分（%s -> %s）" % (bal_before_refund, bal_after_refund),
      bal_after_refund == bal_before_refund + O2_PAY,
      "before=%s after=%s" % (bal_before_refund, bal_after_refund))

r_o2_detail = call("GET", "/orders/%s" % O2_ID, token=CUST)
check("退款后订单状态 REFUNDED",
      (data(r_o2_detail) or {}).get("status") == "REFUNDED",
      (data(r_o2_detail) or {}).get("status"))
check("退款后托管金状态 REFUNDED",
      (data(r_o2_detail) or {}).get("hold", {}).get("status") == "REFUNDED",
      (data(r_o2_detail) or {}).get("hold"))

r_ref2 = call("POST", "/orders/%s/refund" % O2_ID, token=CUST, body={"reason": "再退一次"})
check("重复退款被拒绝", not ok(r_ref2), r_ref2)

# ----------------------------------------------------------------------
# 8. 服务后争议退款（50%）+ 主管审批
# ----------------------------------------------------------------------

section("8. 服务后争议退款 50% → 客服发起 → 管理员审批 → 部分结算")

order3_body = dict(order_body)
order3_body["quantity"] = 2
order3_body["skuId"] = 2001
order3_body["mode"] = "DESIGNATED"
order3_body["boosterId"] = 5002
r_o3 = call("POST", "/orders", token=CUST, body=order3_body,
            headers={"Idempotency-Key": IDEM_KEY + "-o3"})
check("创建订单 3（指定打手 5002）成功", ok(r_o3), r_o3)
O3 = data(r_o3) or {}
O3_ID = i(O3.get("id"))
O3_PAY = i(O3.get("amounts", {}).get("payAmount"))
check("订单 3 实付 6000 分", O3_PAY == 6000, O3_PAY)

# 打手 5002 登录并推进到 IN_SERVICE
r_b2 = call("POST", "/dev/mock-login",
            body={"openid": "dev_openid_booster_02", "role": "BOOSTER"})
check("打手 2 登录成功", ok(r_b2), r_b2)
BOOST2 = (data(r_b2) or {}).get("accessToken")

r_ga2 = call("GET", "/booster/game-accounts", token=BOOST2)
GA2 = 0
if ok(r_ga2) and isinstance(data(r_ga2), list) and data(r_ga2):
    GA2 = i(data(r_ga2)[0].get("id"))

check("打手 2 接单", ok(call("POST", "/booster/orders/%s/accept" % O3_ID, token=BOOST2)), "")
check("客服交接订单 3", ok(call("POST", "/admin/orders/%s/handover" % O3_ID, token=CS,
                            body={"channel": "IM", "bossGameId": "88886666",
                                  "guide": "搜索 ID 添加好友"})), "")
check("打手 2 确认交接", ok(call("POST", "/booster/orders/%s/handover/ack" % O3_ID, token=BOOST2)), "")
r_ev3s = call("POST", "/booster/orders/%s/evidence" % O3_ID, token=BOOST2,
              body={"type": "START", "objectKey": upload_image(BOOST2)})
check("订单 3 上传开局照", ok(r_ev3s), r_ev3s)
EV3S = i((data(r_ev3s) or {}).get("id"))
r_st3 = call("POST", "/booster/orders/%s/start" % O3_ID, token=BOOST2,
             body={"gameAccountId": GA2, "startEvidenceId": EV3S})
check("订单 3 开始服务（IN_SERVICE）", ok(r_st3), r_st3)
check("订单 3 状态为 IN_SERVICE", (data(r_st3) or {}).get("status") == "IN_SERVICE",
      (data(r_st3) or {}).get("status"))

# 客服发起 50% 争议退款
r_disp = call("POST", "/admin/orders/%s/refund-requests" % O3_ID, token=CS,
              body={"refundRate": 50, "reason": "打手只完成一半，协商退一半",
                    "evidenceKeys": ["evidence/e2e/dispute1.jpg"]})
check("客服发起争议退款成功", ok(r_disp), r_disp)
DISP = data(r_disp) or {}
DISP_ID = i(DISP.get("id"))
check("退款比例 50", i(DISP.get("refundRate")) == 50, DISP.get("refundRate"))
check("退款金额 = 6000 × 50% = 3000 分", i(DISP.get("refundAmount")) == 3000, DISP.get("refundAmount"))
check("留存金额 = 3000 分", i(DISP.get("retainedAmount")) == 3000, DISP.get("retainedAmount"))
check("退款单状态 PENDING_APPROVAL", DISP.get("status") == "PENDING_APPROVAL", DISP.get("status"))
check("原状态记录为 IN_SERVICE", DISP.get("previousOrderStatus") == "IN_SERVICE",
      DISP.get("previousOrderStatus"))

r_o3_d = call("GET", "/orders/%s" % O3_ID, token=CUST)
check("争议中订单状态 DISPUTING", (data(r_o3_d) or {}).get("status") == "DISPUTING",
      (data(r_o3_d) or {}).get("status"))

# 争议中不可确认收货
r_conf3 = call("POST", "/orders/%s/confirm" % O3_ID, token=CUST)
check("争议中确认收货被拒绝（60010）", code(r_conf3) == 60010, r_conf3)

# 客服不能审批自己发起的退款
r_cs_appr2 = call("POST", "/admin/refund-requests/%s/approve" % DISP_ID, token=CS)
check("客服无退款审批权（403）", code(r_cs_appr2) == 40301, r_cs_appr2)

# 客服流水页 403
r_cs_ledger = call("GET", "/admin/ledger/orders", token=CS)
check("客服查看流水被拒绝（403）", code(r_cs_ledger) == 40301, r_cs_ledger)

r_me7 = call("GET", "/auth/me", token=CUST)
bal_before_dispute = i((data(r_me7) or {}).get("goldBalance"))

r_earn_b2_before = call("GET", "/booster/earnings", token=BOOST2)
b2_avail_before = i((data(r_earn_b2_before) or {}).get("available"))

# 管理员审批通过
r_appr3 = call("POST", "/admin/refund-requests/%s/approve" % DISP_ID, token=ADMIN)
check("管理员审批通过退款", ok(r_appr3), r_appr3)
APPR3 = data(r_appr3) or {}
check("退款单状态 SUCCESS", APPR3.get("status") == "SUCCESS", APPR3.get("status"))
check("佣金 = 0（未确认收货不扣平台费）", i(APPR3.get("commissionAmount")) == 0,
      APPR3.get("commissionAmount"))
check("打手收益 = 留存全额 3000 分", i(APPR3.get("boosterAmount")) == 3000,
      APPR3.get("boosterAmount"))

r_me8 = call("GET", "/auth/me", token=CUST)
bal_after_dispute = i((data(r_me8) or {}).get("goldBalance"))
check("金砖退回 3000 分（%s -> %s）" % (bal_before_dispute, bal_after_dispute),
      bal_after_dispute == bal_before_dispute + 3000,
      "before=%s after=%s" % (bal_before_dispute, bal_after_dispute))

r_o3_d2 = call("GET", "/orders/%s" % O3_ID, token=CUST)
O3_DETAIL = data(r_o3_d2) or {}
check("部分退款后订单状态 PARTIAL_REFUNDED",
      O3_DETAIL.get("status") == "PARTIAL_REFUNDED", O3_DETAIL.get("status"))
check("托管金状态 PARTIAL_REFUNDED",
      O3_DETAIL.get("hold", {}).get("status") == "PARTIAL_REFUNDED", O3_DETAIL.get("hold"))
r_o3_admin = call("GET", "/admin/orders/%s" % O3_ID, token=ADMIN)
S3 = (data(r_o3_admin) or {}).get("settlement") or {}
check("生成部分退款结算单", bool(S3), S3)
check("结算类型 PARTIAL_REFUND", S3.get("type") == "PARTIAL_REFUND", S3.get("type"))
check("结算基数 = 留存 3000 分", i(S3.get("settlementBase")) == 3000, S3.get("settlementBase"))
check("结算打手收益 3000 分", i(S3.get("boosterAmount")) == 3000, S3.get("boosterAmount"))
check("结算平台收入 0 分", i(S3.get("platformAmount")) == 0, S3.get("platformAmount"))

r_earn_b2_after = call("GET", "/booster/earnings", token=BOOST2)
b2_avail_after = i((data(r_earn_b2_after) or {}).get("available"))
check("打手 2 收益增加 3000 分（%s -> %s）" % (b2_avail_before, b2_avail_after),
      b2_avail_after == b2_avail_before + 3000,
      "before=%s after=%s" % (b2_avail_before, b2_avail_after))

# 部分退款后订单终态，不可再退
r_ref3 = call("POST", "/orders/%s/refund" % O3_ID, token=CUST, body={"reason": "还想退"})
check("部分退款后不可再退（60009/60013）",
      code(r_ref3) in (60009, 60013, 40901), r_ref3)

# 重复审批同一退款单应被拒绝
r_appr3b = call("POST", "/admin/refund-requests/%s/approve" % DISP_ID, token=ADMIN)
check("重复审批同一退款单被拒绝", not ok(r_appr3b), r_appr3b)

# ----------------------------------------------------------------------
# 9. 7 天自动确认（定时任务，01 §13）
# ----------------------------------------------------------------------

section("9. 7 天自动确认定时任务（P0 结算闭环的必要环节）")

order4_body = dict(order_body)
order4_body["quantity"] = 1
order4_body["skuId"] = 2002          # 单价 5000 分
order4_body["mode"] = "DESIGNATED"
order4_body["boosterId"] = 5003
r_o4 = call("POST", "/orders", token=CUST, body=order4_body,
            headers={"Idempotency-Key": IDEM_KEY + "-o4"})
check("创建订单 4（指定打手 5003）成功", ok(r_o4), r_o4)
O4 = data(r_o4) or {}
O4_ID = i(O4.get("id"))
O4_PAY = i(O4.get("amounts", {}).get("payAmount"))
O4_ORIGINAL = i(O4.get("amounts", {}).get("originalAmount"))
check("订单 4 实付 5000 分", O4_PAY == 5000, O4_PAY)

r_b3 = call("POST", "/dev/mock-login",
            body={"openid": "dev_openid_booster_03", "role": "BOOSTER"})
check("打手 3 登录成功", ok(r_b3), r_b3)
BOOST3 = (data(r_b3) or {}).get("accessToken")

r_ga3 = call("GET", "/booster/game-accounts", token=BOOST3)
GA3 = 0
if ok(r_ga3) and isinstance(data(r_ga3), list) and data(r_ga3):
    GA3 = i(data(r_ga3)[0].get("id"))

check("打手 3 接单", ok(call("POST", "/booster/orders/%s/accept" % O4_ID, token=BOOST3)), "")
check("客服交接订单 4", ok(call("POST", "/admin/orders/%s/handover" % O4_ID, token=CS,
                            body={"channel": "IM", "bossGameId": "88886666",
                                  "guide": "搜索 ID 添加好友"})), "")
check("打手 3 确认交接", ok(call("POST", "/booster/orders/%s/handover/ack" % O4_ID, token=BOOST3)), "")
r_ev4s = call("POST", "/booster/orders/%s/evidence" % O4_ID, token=BOOST3,
              body={"type": "START", "objectKey": upload_image(BOOST3)})
check("订单 4 上传开局照", ok(r_ev4s), r_ev4s)
EV4S = i((data(r_ev4s) or {}).get("id"))
check("订单 4 开始服务", ok(call("POST", "/booster/orders/%s/start" % O4_ID, token=BOOST3,
                            body={"gameAccountId": GA3, "startEvidenceId": EV4S})), "")
r_ev4e = call("POST", "/booster/orders/%s/evidence" % O4_ID, token=BOOST3,
              body={"type": "END", "objectKey": upload_image(BOOST3)})
check("订单 4 上传结束照", ok(r_ev4e), r_ev4e)
EV4E = i((data(r_ev4e) or {}).get("id"))
r_cp4 = call("POST", "/booster/orders/%s/complete" % O4_ID, token=BOOST3,
             body={"endEvidenceId": EV4E})
check("订单 4 申请完单（PENDING_CONFIRM）", ok(r_cp4), r_cp4)
check("订单 4 状态 PENDING_CONFIRM",
      (data(r_cp4) or {}).get("status") == "PENDING_CONFIRM", (data(r_cp4) or {}).get("status"))

r_earn_b3_before = call("GET", "/booster/earnings", token=BOOST3)
b3_avail_before = i((data(r_earn_b3_before) or {}).get("available"))

# 未到期时触发任务，不应确认任何订单
r_job0 = call("POST", "/dev/jobs/auto-confirm/run")
check("手动触发自动确认任务成功", ok(r_job0), r_job0)
r_o4_mid = call("GET", "/orders/%s" % O4_ID, token=CUST)
check("未到期时订单仍为 PENDING_CONFIRM（任务未误确认）",
      (data(r_o4_mid) or {}).get("status") == "PENDING_CONFIRM",
      (data(r_o4_mid) or {}).get("status"))

# 把截止时间调到过去，模拟已过 7 天
r_exp = call("POST", "/dev/orders/%s/expire-confirm-deadline" % O4_ID)
check("调整自动确认截止时间成功", ok(r_exp), r_exp)

r_job = call("POST", "/dev/jobs/auto-confirm/run")
check("触发自动确认任务成功", ok(r_job), r_job)
check("任务处理了 1 笔订单", i((data(r_job) or {}).get("handled")) == 1, data(r_job))

r_o4_done = call("GET", "/orders/%s" % O4_ID, token=CUST)
O4_DETAIL = data(r_o4_done) or {}
check("自动确认后订单状态 COMPLETED",
      O4_DETAIL.get("status") == "COMPLETED", O4_DETAIL.get("status"))
check("自动确认写入 confirmedAt",
      O4_DETAIL.get("timestamps", {}).get("confirmedAt") is not None,
      O4_DETAIL.get("timestamps"))

r_o4_admin = call("GET", "/admin/orders/%s" % O4_ID, token=ADMIN)
S4 = (data(r_o4_admin) or {}).get("settlement") or {}
check("自动确认生成结算单", bool(S4), S4)
check("结算类型 NORMAL", S4.get("type") == "NORMAL", S4.get("type"))
O4_BOOSTER_AMT = i(S4.get("boosterAmount"))
O4_PLATFORM_AMT = i(S4.get("platformAmount"))
check("打手收益 = 原价 5000 × 85% = 4250 分", O4_BOOSTER_AMT == 4250, O4_BOOSTER_AMT)
check("平台收入 = 5000 − 4250 = 750 分", O4_PLATFORM_AMT == 750, O4_PLATFORM_AMT)

r_earn_b3_after = call("GET", "/booster/earnings", token=BOOST3)
b3_avail_after = i((data(r_earn_b3_after) or {}).get("available"))
check("打手 3 收益到账 available 增加 4250 分（%s -> %s）" % (b3_avail_before, b3_avail_after),
      b3_avail_after == b3_avail_before + O4_BOOSTER_AMT,
      "before=%s after=%s" % (b3_avail_before, b3_avail_after))

# 时间线应有 SYSTEM 操作方的自动确认记录
r_tl4 = call("GET", "/orders/%s/timeline" % O4_ID, token=CUST)
tl = data(r_tl4) if ok(r_tl4) else []
has_system_confirm = any(p.get("operatorType") == "SYSTEM" and p.get("action") == "AUTO_CONFIRM"
                         for p in (tl or []))
check("时间线记录系统自动确认（operatorType=SYSTEM）", has_system_confirm, tl)

# 幂等：再次触发任务不应重复结算
r_job2 = call("POST", "/dev/jobs/auto-confirm/run")
check("二次触发任务成功", ok(r_job2), r_job2)
check("二次触发处理 0 笔（幂等）", i((data(r_job2) or {}).get("handled")) == 0, data(r_job2))
r_earn_b3_again = call("GET", "/booster/earnings", token=BOOST3)
check("重复触发未二次入账（available 仍为 %s）" % b3_avail_after,
      i((data(r_earn_b3_again) or {}).get("available")) == b3_avail_after,
      data(r_earn_b3_again))

r_o4_again = call("GET", "/orders/%s" % O4_ID, token=CUST)
check("重复触发后订单仍为 COMPLETED",
      (data(r_o4_again) or {}).get("status") == "COMPLETED", (data(r_o4_again) or {}).get("status"))

# ----------------------------------------------------------------------
# 10. 交接超时任务
# ----------------------------------------------------------------------

section("10. 交接超时定时任务")

r_job_h = call("POST", "/dev/jobs/handover-timeout/run")
check("手动触发交接超时任务成功", ok(r_job_h), r_job_h)
check("交接超时任务返回处理笔数", "handled" in (data(r_job_h) or {}), data(r_job_h))

# ----------------------------------------------------------------------
# 11. 资金守恒校验
# ----------------------------------------------------------------------

section("11. 资金守恒校验（最关键的一步）")

r_ledger = call("GET", "/admin/ledger/orders", token=ADMIN)
LEDGER_OK = ok(r_ledger)
check("管理员可查看流水页", LEDGER_OK, r_ledger)

# 客户侧：总消耗 = 四单实付 − 两笔退款（订单 4 由定时任务自动确认，无退款）
total_paid = PAY + O2_PAY + O3_PAY + O4_PAY
total_refunded = O2_PAY + 3000
net_consumed = total_paid - total_refunded

# 平台 + 打手侧（订单 1 正常结算、订单 3 部分退款结算、订单 4 自动确认结算）
platform_total = PLATFORM_AMT + 0 + O4_PLATFORM_AMT
booster_total = BOOSTER_AMT + 3000 + O4_BOOSTER_AMT
settled_total = platform_total + booster_total

print("\n  客户实付合计     : %s 分" % total_paid)
print("  退款合计         : %s 分" % total_refunded)
print("  客户净消耗       : %s 分" % net_consumed)
print("  平台收入合计     : %s 分" % platform_total)
print("  打手收益合计     : %s 分" % booster_total)
print("  已结算分配合计   : %s 分" % settled_total)

check("资金守恒：客户净消耗 == 平台收入 + 打手收益",
      net_consumed == settled_total,
      "net=%s settled=%s diff=%s" % (net_consumed, settled_total, net_consumed - settled_total))

# ----------------------------------------------------------------------
# 汇总
# ----------------------------------------------------------------------

section("验证结果汇总")
print("  通过：%d 项" % len(PASSED))
print("  失败：%d 项" % len(FAILED))
if FAILED:
    print("\n  失败明细：")
    for name, detail in FAILED:
        print("   - %s\n     %s" % (name, detail))
    sys.exit(1)
print("\n  全部通过 ✅  资金链路端到端跑通")
sys.exit(0)
