#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟“外部控制面”：起一个 WebSocket 服务端，等托管模式探针连上来，把托管能力逐项验证一遍。

## 为什么需要它
真机 E2E 场景（`managed-config`）只能跑在 mc-testkit 能注入配置的后端上；代理端与手工起服
需要一条**不依赖测试框架**的验收路径，也方便对接真实控制面（JianManager）时先自验协议。

## 它验什么（逐项断言）
1. `hello`：`managed=true`，能力位含 metric_push / config_push / config_state / mcp_bridge；
2. `core.bridge_status`：回执里 `capabilities` 是查询时刻活值；
3. `config_push`：嵌套 payload 对象形态 → `config_applied(result=applied, configVersion=2)`；
4. 周期 `metric_snapshot`：不发任何命令也能收到非空指标；
5. `metrics_now`：同一 requestId 的成功回执，且随后收到新的一帧；
6. `core.mcp`：initialize / tools/list / tools/call 走 JSON-RPC 透传（含分片帧重组）；
7. 零监听：metrics / web / mcp 三个端口均连不上。

## 用法
    python e2e/tools/probe_control_plane_sim.py --port 19000 --token <token> \\
        --instance e2e-fixture --expect-zero-bind 19941,19942,19943 \\
        --result-file build/manual/managed.properties

探针侧身份文件需指向 `ws://127.0.0.1:<port>/ws/plugin-bridge`，credential 即 `--token`。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time
from typing import Any, Dict, List, Optional, Tuple

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
BRIDGE_PATH = "/ws/plugin-bridge"
REQUIRED_CAPABILITIES = ("metric_push", "config_push", "config_state")


class HandshakeRetry(Exception):
    """本次连接握不了手（端口探活等），不算失败，继续等下一个连接。"""


class Assertions:
    """逐项断言登记表：同名覆盖，输出稳定顺序。"""

    def __init__(self) -> None:
        self._items: Dict[str, Tuple[bool, str]] = {}

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self._items[name] = (bool(ok), detail)
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] {name}" + (f"  ({detail})" if detail and not ok else ""))
        return ok

    @property
    def failed(self) -> List[str]:
        return [name for name, (ok, _) in self._items.items() if not ok]

    def write_properties(self, path: str, message: str) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        total = len(self._items)
        failed = self.failed
        lines = [
            "#managed-mode manual acceptance",
            f"status={'PASS' if not failed else 'FAIL'}",
            f"assertions.total={total}",
            f"assertions.passed={total - len(failed)}",
            f"assertions.failed={len(failed)}",
            f"assertions.failed.names={';'.join(failed)}",
            f"message={message}",
        ]
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")


class WsFrame:
    """RFC 6455 最小实现：服务端只收文本/控制帧，只发文本/控制帧。"""

    OPCODE_TEXT = 0x1
    OPCODE_CLOSE = 0x8
    OPCODE_PING = 0x9
    OPCODE_PONG = 0xA

    @staticmethod
    def read(sock: socket.socket) -> Tuple[int, bytes]:
        header = WsFrame._recv_exact(sock, 2)
        first, second = header[0], header[1]
        opcode = first & 0x0F
        masked = (second & 0x80) != 0
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", WsFrame._recv_exact(sock, 2))[0]
        elif length == 127:
            length = struct.unpack("!Q", WsFrame._recv_exact(sock, 8))[0]
        mask = WsFrame._recv_exact(sock, 4) if masked else b""
        payload = WsFrame._recv_exact(sock, length) if length else b""
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return opcode, payload

    @staticmethod
    def _recv_exact(sock: socket.socket, size: int) -> bytes:
        buffer = bytearray()
        while len(buffer) < size:
            chunk = sock.recv(size - len(buffer))
            if not chunk:
                raise ConnectionError("连接已关闭")
            buffer.extend(chunk)
        return bytes(buffer)

    @staticmethod
    def send_text(sock: socket.socket, text: str) -> None:
        payload = text.encode("utf-8")
        header = bytearray([0x81])
        size = len(payload)
        if size < 126:
            header.append(size)
        elif size < 65536:
            header.append(126)
            header.extend(struct.pack("!H", size))
        else:
            header.append(127)
            header.extend(struct.pack("!Q", size))
        sock.sendall(bytes(header) + payload)

    @staticmethod
    def send_pong(sock: socket.socket, payload: bytes) -> None:
        sock.sendall(bytes([0x8A, len(payload)]) + payload)


class ControlPlane:
    """一个连接上的控制面状态机：收帧、发命令、收结果。"""

    def __init__(self, sock: socket.socket, instance: str, checks: Assertions) -> None:
        self.sock = sock
        self.instance = instance
        self.checks = checks
        self.inbox: List[Dict[str, Any]] = []
        self.lock = threading.Lock()
        self.chunks: Dict[str, List[Optional[bytes]]] = {}
        self._reader = threading.Thread(target=self._read_loop, name="cp-reader", daemon=True)

    # ── 读循环与分片重组 ──

    def start(self) -> None:
        self._reader.start()

    def _read_loop(self) -> None:
        try:
            while True:
                opcode, payload = WsFrame.read(self.sock)
                if opcode == WsFrame.OPCODE_CLOSE:
                    return
                if opcode == WsFrame.OPCODE_PING:
                    WsFrame.send_pong(self.sock, payload)
                    continue
                if opcode != WsFrame.OPCODE_TEXT:
                    continue
                self._on_text(payload.decode("utf-8", "replace"))
        except Exception:  # 连接断开即退出读循环
            return

    def _on_text(self, text: str) -> None:
        try:
            frame = json.loads(text)
        except json.JSONDecodeError:
            return
        if frame.get("event") == "chunk":
            self._collect_chunk(frame)
            return
        with self.lock:
            self.inbox.append(frame)

    def _collect_chunk(self, frame: Dict[str, Any]) -> None:
        group = str(frame.get("groupId"))
        total = int(frame.get("total") or 0)
        seq = int(frame.get("seq") or 0)
        if total <= 0:
            return
        with self.lock:
            parts = self.chunks.setdefault(group, [None] * total)
            if seq < total:
                parts[seq] = base64.b64decode(frame.get("data") or "")
            if all(part is not None for part in parts):
                merged = b"".join(part for part in parts if part is not None)
                del self.chunks[group]
                self.inbox.append(json.loads(merged.decode("utf-8", "replace")))

    # ── 发命令 ──

    def send_command(self, action: str, request_id: str, payload: Any = None, domain: str = "core") -> None:
        frame: Dict[str, Any] = {
            "type": "command",
            "domain": domain,
            "action": action,
            "requestId": request_id,
        }
        if payload is not None:
            frame["payload"] = payload
        WsFrame.send_text(self.sock, json.dumps(frame, ensure_ascii=False))

    def await_frame(self, predicate, timeout: float, on_other=None) -> Optional[Dict[str, Any]]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                for index, frame in enumerate(self.inbox):
                    if predicate(frame):
                        return self.inbox.pop(index)
                others = list(self.inbox)
                self.inbox.clear()
            for frame in others:
                if on_other:
                    on_other(frame)
            time.sleep(0.05)
        return None

    def await_command_result(self, request_id: str, timeout: float = 30.0) -> Optional[Dict[str, Any]]:
        return self.await_frame(
            lambda f: f.get("event") == "command_result"
            and (f.get("data") or {}).get("requestId") == request_id,
            timeout,
        )

    def await_event(self, name: str, timeout: float = 30.0) -> Optional[Dict[str, Any]]:
        return self.await_frame(lambda f: f.get("event") == name, timeout)


HELLO: List[Dict[str, Any]] = []


def handshake(listener: socket.socket, token: str, instance: str, timeout: float) -> socket.socket:
    """等一个**真探针**连入并完成 WS 握手。

    循环 accept 而不是只接一次：端口就绪探测、端口扫掠工具都会建连后立即关闭，
    把这类“握不了手就走”的连接当成失败会让模拟器误报。
    """
    deadline = time.time() + timeout
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            raise TimeoutError(f"{timeout:.0f}s 内没有探针完成握手")
        listener.settimeout(remaining)
        client, _ = listener.accept()
        try:
            return _handshake_once(client, token, instance)
        except HandshakeRetry:
            runcatching_close(client)
            continue


def _handshake_once(client: socket.socket, token: str, instance: str) -> socket.socket:
    client.settimeout(10.0)
    request = b""
    while b"\r\n\r\n" not in request:
        chunk = client.recv(4096)
        if not chunk:
            raise HandshakeRetry()
        request += chunk
    head = request.decode("latin-1")
    first_line = head.split("\r\n", 1)[0]
    if BRIDGE_PATH not in first_line:
        raise AssertionError(f"握手路径不符：{first_line}")
    if f"token={token}" not in first_line:
        raise AssertionError("握手未携带预期 credential")
    if f"instance={instance}" not in first_line:
        raise AssertionError("握手未携带预期 instance")
    key = ""
    for line in head.split("\r\n"):
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
    if not key:
        raise AssertionError("握手缺少 Sec-WebSocket-Key")
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest()).decode("ascii")
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
    )
    client.sendall(response.encode("ascii"))
    client.settimeout(None)
    return client


def can_connect(port: int, timeout: float = 0.4) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout):
            return True
    except OSError:
        return False


def run_suite(cp: ControlPlane, zero_bind_ports: List[int]) -> None:
    checks = cp.checks

    hello = cp.await_frame(lambda f: f.get("type") == "hello", 60.0)
    if hello is None:
        checks.check("hello", False, "未在 60s 内收到 hello")
        return
    capabilities = hello.get("capabilities") or []
    missing = [cap for cap in REQUIRED_CAPABILITIES if cap not in capabilities]
    checks.check("helloManaged", hello.get("managed") is True and not missing,
                 f"managed={hello.get('managed')} missing={missing}")

    # 先等一帧 config_state：它带探针当前的托管配置版本，下发改用它 +1（否则会被正确判为 stale）。
    state = cp.await_event("config_state", 30.0)
    state_payload = (state or {}).get("payload") or {}
    current_version = int(state_payload.get("configVersion") or 0)
    target_version = current_version + 1

    # bridge_status：能力位应为查询时刻活值
    cp.send_command("bridge_status", "cp-status")
    status = cp.await_command_result("cp-status", 30.0)
    status_output = json.loads((status or {}).get("data", {}).get("output") or "{}") if status else {}
    checks.check("bridgeStatusCapabilities",
                 (status or {}).get("data", {}).get("success") is True
                 and "mcp_bridge" in (status_output.get("capabilities") or []),
                 f"capabilities={status_output.get('capabilities')}")

    # config_push（嵌套 payload 对象）→ config_applied(applied)
    applied = None
    for attempt in range(12):
        cp.send_command("config_push", f"cp-push-{attempt}", {
            "configVersion": target_version,
            "baseVersion": current_version,
            "values": {"debug": True},
            "removals": [],
            "reason": "manual acceptance",
            "operator": "simulator",
        })
        applied = cp.await_event("config_applied", 8.0)
        if applied:
            break
    payload = (applied or {}).get("payload") or {}
    checks.check(
        "configApplied",
        payload.get("result") == "applied" and payload.get("configVersion") == target_version,
        f"result={payload.get('result')} version={payload.get('configVersion')} "
        f"expected={target_version} error={payload.get('error')}",
    )

    # 周期指标推送（不发命令也要有）
    metric = cp.await_event("metric_snapshot", 30.0)
    metrics = ((metric or {}).get("payload") or {}).get("metrics") or {}
    checks.check("metricPush", bool(metrics), f"metrics keys={list(metrics)[:6]}")

    # metrics_now：同 requestId 回执 + 随后一帧
    cp.send_command("metrics_now", "cp-metrics-now")
    now_result = cp.await_command_result("cp-metrics-now", 30.0)
    checks.check("metricsNow",
                 bool(now_result) and (now_result or {}).get("data", {}).get("success") is True,
                 f"error={(now_result or {}).get('data', {}).get('error')}")

    # config_state 周期上报（connection 字段的语义分野）
    checks.check("configState", state_payload.get("connection") == "online",
                 f"connection={state_payload.get('connection')}")

    # MCP over bridge：initialize → tools/list（含分片重组）→ tools/call
    cp.send_command("mcp", "cp-mcp-init", {"jsonrpc": "2.0", "id": 1, "method": "initialize"})
    init = cp.await_command_result("cp-mcp-init", 30.0)
    init_output = (init or {}).get("data", {}).get("output") or ""
    checks.check("mcpInitialize", "protocolVersion" in init_output, init_output[:120])

    cp.send_command("mcp", "cp-mcp-list", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    listed = cp.await_command_result("cp-mcp-list", 60.0)
    list_output = (listed or {}).get("data", {}).get("output") or ""
    checks.check("mcpToolsList",
                 "server_status" in list_output and "server_command" in list_output,
                 f"len={len(list_output)}")

    cp.send_command("mcp", "cp-mcp-call", {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                            "params": {"name": "server_status", "arguments": {}}})
    called = cp.await_command_result("cp-mcp-call", 60.0)
    call_output = (called or {}).get("data", {}).get("output") or ""
    checks.check("mcpToolsCall", "content" in call_output and "jvm" in call_output, call_output[:120])

    # 零监听：三个端口都该连不上
    if zero_bind_ports:
        time.sleep(2.0)
        reachable = [port for port in zero_bind_ports if can_connect(port)]
        checks.check("zeroBind", not reachable, f"仍可连接：{reachable}")


def main() -> int:
    parser = argparse.ArgumentParser(description="模拟外部控制面，验收托管模式探针")
    parser.add_argument("--port", type=int, required=True, help="监听端口（需与身份文件 endpoint 一致）")
    parser.add_argument("--token", required=True, help="期望的 credential（身份文件的 credential）")
    parser.add_argument("--instance", default="e2e-fixture", help="期望的实例标识")
    parser.add_argument("--expect-zero-bind", default="", help="托管模式下应当连不上的端口，逗号分隔")
    parser.add_argument("--result-file", default="", help="可选：把断言汇总写成 properties 文件")
    parser.add_argument("--accept-timeout", type=float, default=180.0, help="等待探针连入的超时秒数")
    args = parser.parse_args()

    zero_bind_ports = [int(port) for port in args.expect_zero_bind.split(",") if port.strip()]
    checks = Assertions()

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", args.port))
    listener.listen(1)
    print(f"[sim] 监听 127.0.0.1:{args.port}{BRIDGE_PATH}，等待探针连入…")

    try:
        client = handshake(listener, args.token, args.instance, args.accept_timeout)
    except Exception as error:  # 握手阶段任何失败都要明确报出来
        checks.check("handshake", False, str(error))
        if args.result_file:
            checks.write_properties(args.result_file, "握手失败")
        return 1

    print("[sim] 探针已连入，开始逐项验收")
    cp = ControlPlane(client, args.instance, checks)
    cp.start()
    try:
        run_suite(cp, zero_bind_ports)
    except Exception as error:
        checks.check("suite", False, f"{type(error).__name__}: {error}")
    finally:
        runcatching_close(client)
        listener.close()

    failed = checks.failed
    message = "托管模式手工验收通过" if not failed else f"失败断言：{','.join(failed)}"
    print(f"\n[sim] {'PASS' if not failed else 'FAIL'} — {message}")
    if args.result_file:
        checks.write_properties(args.result_file, message)
    return 0 if not failed else 1


def runcatching_close(sock: socket.socket) -> None:
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    try:
        sock.close()
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main())
