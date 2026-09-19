# -*- coding: utf-8 -*-
"""
rcon_bridge - MCDR 内置 RCON 服务端

背景：MCDR 自带的 ``rcon`` 配置是 RCON *客户端*（MCDR 去连 MC 原版 RCON），
MCDR 本身并不监听任何 RCON 端口。本插件补上这一环：在 MCDR 内起一个
SOURCE RCON 协议的 TCP 服务端，外部（如 Velocity 插件 mclink-velocity、
mcrcon 等标准 RCON 客户端）即可远程执行指令并拿回输出。

指令分发规则：
- ``!!`` 开头          -> MCDR 指令系统（``!!pb list``、``!!MCDR status`` 等），
                          用自定义 CommandSource 捕获插件 reply 的输出
- 其它（含 ``/`` 开头）-> 原版指令，发送到服务端 stdin，捕获服务端日志回显

执行者信息（v1.2.0+）：
AstrBot 发送指令时把执行者信息以 JSON 附在指令尾部，用控制字符 \\x01 分隔
（``!!pb list\\x01{"qq": "10001", "nickname": "张三"}``），经 Velocity 原样透传。
插件收到后剥离元数据，用干净指令执行，并：
- 在 MCDR 控制台记录 ``RCON 执行指令: !!pb list (QQ 10001)``
- 在游戏内广播 ``[QQ] 张三 执行了指令: !!pb list``
不带元数据的指令（如 mcrcon 手动调试）行为不变。

输出按 RCON 协议回传（>3800 字节自动分包）。PrimeBackup 等插件异步回复，
执行后等待输出安静（停顿 0.4s）再返回，最长等 2s。

协议实现：
- 标准 SOURCE RCON 包格式（小端：length | request_id | type | body + 2 个 \\0）
- 登录（type=3）成功回 request_id、失败回 -1；命令（type=2）回 request_id + 输出
- 响应超过 3800 字节自动按 UTF-8 字符边界分包；空输出回一个空 body 包
- 收到未知 type（含 RCON 探针包 type=100）时回一个同 request_id 的空响应，
  与 MCDR RconConnection / mclink-velocity 的 ENDING_PROBE 收尾方式兼容

配置：config/rcon_bridge/config.json（首次加载自动生成）
"""
import hmac
import json
import re
import socket
import socketserver
import threading
import time

from mcdreforged.api.all import (
    PluginServerInterface,
    Literal,
    RText,
    RColor,
    RTextList,
)
from mcdreforged.command.command_source import CommandSource
from mcdreforged.minecraft.rtext.text import RTextBase

PLUGIN_ID = 'rcon_bridge'
PLUGIN_VERSION = '1.2.2'

PLUGIN_METADATA = {
    'id': 'rcon_bridge',
    'version': PLUGIN_VERSION,
    'name': 'RconBridge',
    'description': 'MCDR 内置 RCON 服务端：供 mclink-velocity / mcrcon 等标准 RCON 客户端远程执行指令并回传输出',
    'author': ['Sjym'],
    'link': 'https://github.com/s-j-y-m/astrbot_plugin_mc_butler_VC-MCDR',
}

DEFAULT_CONFIG = {
    'enable': True,
    'host': '127.0.0.1',
    'port': 30166,
    'password': 'sk-651c2f35f5',  # 默认占位符（sk- 前缀），须与 Velocity 插件 config.yml 的 rcon-password 一致
    'native_output_wait': 2.0,   # 原版指令执行后收集输出的最长等待（秒）
    'native_output_quiet': 0.4,  # 连续该时长没有新输出则提前认为收集结束（秒）
    'socket_timeout': 60,        # RCON 连接空闲超时（秒）
}

# 包体上限：MC 生态普遍按 4096 分片，留出余量并避免触发客户端兼容性问题
MAX_BODY = 3800
MAX_PACKET = 4110

TYPE_LOGIN = 3
TYPE_COMMAND = 2
TYPE_RESPONSE = 0

# 执行者元数据分隔符：AstrBot 把 JSON 附在指令尾部，如 `!!pb list\x01{"qq":...}`
METADATA_SEPARATOR = '\x01'

COLOR_CODE = re.compile(r'§[0-9a-fk-orx]', re.IGNORECASE)


def _to_plain(message) -> str:
    """把 str / RText / RText 序列 统一转成纯文本。"""
    if message is None:
        return ''
    if isinstance(message, str):
        return message
    if isinstance(message, RTextBase):
        return message.to_plain_text()
    if hasattr(message, '__iter__'):
        return '\n'.join(_to_plain(m) for m in message)
    return str(message)


def _strip_color(text: str) -> str:
    return COLOR_CODE.sub('', text)


def _sanitize_name(name: str) -> str:
    """昵称去掉颜色码与换行，截断到 32 字符，避免污染日志与游戏内广播。"""
    name = _strip_color(str(name or ''))
    name = re.sub(r'\s+', ' ', name).strip()
    return name[:32]


def _chunk_text(text: str, limit: int = MAX_BODY):
    """按 UTF-8 字符边界把文本切成不超过 limit 字节的若干段。"""
    if not text:
        return ['']
    chunks, cur, cur_len = [], [], 0
    for ch in text:
        n = len(ch.encode('utf-8'))
        if cur_len + n > limit and cur:
            chunks.append(''.join(cur))
            cur, cur_len = [], 0
        cur.append(ch)
        cur_len += n
    if cur:
        chunks.append(''.join(cur))
    return chunks


class _OutputCapture:
    """on_info 事件期间的输出收集窗口（原版指令输出走服务端日志回流）。"""

    def __init__(self):
        self._lock = threading.Lock()
        self._lines: list = []
        self._until = 0.0

    def start(self, wait_seconds: float):
        with self._lock:
            self._lines.clear()
            self._until = time.time() + wait_seconds

    def feed(self, line: str):
        with self._lock:
            if time.time() < self._until:
                self._lines.append(line)

    def collect(self) -> list:
        with self._lock:
            return list(self._lines)


class RconCommandSource(CommandSource):
    """RCON 会话对应的命令源：最高权限，reply 内容进缓冲区。"""

    def __init__(self, server: PluginServerInterface, sink: list):
        self.__server = server
        self.__sink = sink

    def get_server(self) -> PluginServerInterface:
        return self.__server

    def get_permission_level(self) -> int:
        # RCON 调用方已在 Velocity / QQ 侧做过管理员校验，这里给满级
        return 4

    def reply(self, message, **kwargs) -> None:
        text = _to_plain(message)
        for line in text.splitlines() or ['']:
            self.__sink.append(line)


class _RconRequestHandler(socketserver.BaseRequestHandler):
    def setup(self):
        self.request.settimeout(self.server.socket_timeout)

    def handle(self):
        client = f'{self.client_address[0]}:{self.client_address[1]}'
        authed = False
        try:
            while True:
                packet = self._read_packet()
                if packet is None:
                    break
                req_id, ptype, body = packet
                if ptype == TYPE_LOGIN:
                    if self.server.bridge.check_password(body):
                        authed = True
                        self._send(req_id, TYPE_RESPONSE, '')
                    else:
                        self._send(-1, TYPE_RESPONSE, '')
                        # 登录失败需要留痕（安全审计），成功连接静默不刷屏
                        self.server.bridge.warn(f'{client} 登录失败：密码错误')
                        break
                elif ptype == TYPE_COMMAND:
                    if not authed:
                        self._send(-1, TYPE_RESPONSE, 'Not authenticated')
                        break
                    output = self.server.bridge.dispatch(body)
                    for chunk in _chunk_text(output):
                        self._send(req_id, TYPE_RESPONSE, chunk)
                else:
                    # 未知类型（如 ENDING_PROBE 探针包）：回同 id 空响应，让对端结束收包
                    if authed:
                        self._send(req_id, TYPE_RESPONSE, '')
        except (socket.timeout, TimeoutError):
            pass
        except OSError:
            pass
        finally:
            try:
                self.request.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def _read_packet(self):
        header = self._read_exact(4)
        if header is None:
            return None
        length = int.from_bytes(header, 'little')
        if length < 10 or length > MAX_PACKET:
            raise OSError(f'非法 RCON 包长度: {length}')
        rest = self._read_exact(length)
        if rest is None:
            return None
        req_id = int.from_bytes(rest[0:4], 'little', signed=True)
        ptype = int.from_bytes(rest[4:8], 'little', signed=True)
        body = rest[8:length - 2].decode('utf-8', errors='replace')
        return req_id, ptype, body

    def _read_exact(self, n: int):
        buf = b''
        while len(buf) < n:
            try:
                chunk = self.request.recv(n - len(buf))
            except (socket.timeout, TimeoutError):
                return None
            if not chunk:
                return None
            buf += chunk
        return buf

    def _send(self, req_id: int, ptype: int, body: str):
        payload = body.encode('utf-8')
        length = 4 + 4 + len(payload) + 2
        data = (length.to_bytes(4, 'little')
                + req_id.to_bytes(4, 'little', signed=True)
                + ptype.to_bytes(4, 'little', signed=True)
                + payload + b'\x00\x00')
        self.request.sendall(data)


class _RconServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, handler, bridge):
        self.bridge = bridge
        self.socket_timeout = bridge.config.get('socket_timeout', 60)
        super().__init__(address, handler)


class RconBridge:
    def __init__(self, server: PluginServerInterface, config: dict):
        self.server = server
        self.config = config
        self._tcp_server: _RconServer = None
        self._thread: threading.Thread = None
        self._capture = _OutputCapture()
        # 串行化指令分发，避免并发 RCON 命令时原版输出捕获互相串扰
        self._dispatch_lock = threading.Lock()

    # ---- 生命周期 ----

    def start(self):
        if not self.config.get('enable', True):
            self.log('插件未启用（enable: false），RCON 服务端不启动')
            return
        host = self.config.get('host', '127.0.0.1')
        port = int(self.config.get('port', 30166))
        try:
            self._tcp_server = _RconServer((host, port), _RconRequestHandler, self)
        except OSError as e:
            self.server.logger.error(f'监听 {host}:{port} 失败：{e}')
            return
        self._thread = threading.Thread(
            target=self._tcp_server.serve_forever,
            name=f'{PLUGIN_ID}-listener',
            daemon=True,
        )
        self._thread.start()
        self.log(f'RCON 服务端已监听 {host}:{port}')

    def stop(self):
        if self._tcp_server is not None:
            self._tcp_server.shutdown()
            self._tcp_server.server_close()
            self._tcp_server = None
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None
            self.log('RCON 服务端已停止')

    def log(self, message: str):
        # MCDR 插件 logger 会自动带 [rcon_bridge]: 前缀，这里不再重复加
        self.server.logger.info(message)

    def warn(self, message: str):
        self.server.logger.warning(message)

    # ---- 协议辅助 ----

    def check_password(self, password: str) -> bool:
        expected = str(self.config.get('password', ''))
        if not expected:
            return False
        return hmac.compare_digest(expected.encode('utf-8'), password.encode('utf-8'))

    # ---- 指令分发 ----

    def _split_executor(self, command: str) -> tuple[str, dict | None]:
        """剥离附在指令尾部的执行者元数据：`指令\\x01{"qq":...}` -> (指令, {qq, nickname})。"""
        if METADATA_SEPARATOR not in command:
            return command, None
        clean, _, meta_raw = command.partition(METADATA_SEPARATOR)
        try:
            data = json.loads(meta_raw)
        except Exception:  # noqa: BLE001
            self.warn(f'指令尾部元数据不是合法 JSON，已忽略: {meta_raw[:80]}')
            return clean.strip(), None
        if not isinstance(data, dict):
            self.warn(f'指令尾部元数据不是 JSON 对象，已忽略: {meta_raw[:80]}')
            return clean.strip(), None
        qq = str(data.get('qq') or '').strip()
        nickname = str(data.get('nickname') or '').strip()
        if qq or nickname:
            return clean.strip(), {'qq': qq, 'nickname': nickname}
        return clean.strip(), None

    def dispatch(self, command: str) -> str:
        command = (command or '').strip()
        if not command:
            return ''
        command, executor = self._split_executor(command)
        if not command:
            return ''
        qq = str((executor or {}).get('qq') or '')
        nickname = _sanitize_name(str((executor or {}).get('nickname') or ''))
        if qq:
            self.log(f'RCON 执行指令: {command} (QQ {qq})')
        else:
            self.log(f'RCON 执行指令: {command}')
        with self._dispatch_lock:
            if command.startswith('!!'):
                output = self._run_mcdr_command(command)
            else:
                output = self._run_native_command(command)
        # 广播放在输出收集之后：broadcast 底层走 tellraw，会在服务端日志留回显，
        # 先广播会混进原版指令的输出捕获里
        if qq or nickname:
            self._broadcast_command(nickname, qq, command)
        # 统一去掉 § 颜色/样式码，避免污染 QQ 侧显示
        return _strip_color(output).strip()

    def _broadcast_command(self, nickname: str, qq: str, command: str):
        who = nickname or f'QQ {qq}'
        try:
            self.server.broadcast(RTextList(
                RText('[QQ] ', RColor.dark_aqua),
                RText(who, RColor.white),
                RText(' 执行了指令: ', RColor.gray),
                RText(command, RColor.yellow),
            ))
        except Exception:  # noqa: BLE001
            self.server.logger.exception('广播执行者信息失败')

    def _run_mcdr_command(self, command: str) -> str:
        lines: list = []
        source = RconCommandSource(self.server, lines)
        try:
            self.server.execute_command(command, source)
        except Exception as e:  # noqa: BLE001
            self.server.logger.exception('MCDR 指令执行异常')
            return f'指令执行出错: {e}'
        # PrimeBackup 等插件把指令作为任务丢进线程池，reply 发生在工作线程里，
        # execute_command 返回不代表输出已产生，等输出安静后再收
        self._wait_output_quiet(lambda: len(lines))
        return '\n'.join(lines)

    def _wait_output_quiet(self, count_fn) -> None:
        """轮询输出条数，出现 quiet 秒无新增即认为输出结束，最长等 wait 秒。"""
        wait = float(self.config.get('native_output_wait', 2.0))
        quiet = float(self.config.get('native_output_quiet', 0.4))
        deadline = time.time() + wait
        last_count, last_change = count_fn(), time.time()
        while time.time() < deadline:
            time.sleep(0.05)
            count = count_fn()
            if count != last_count:
                last_count, last_change = count, time.time()
            elif count > 0 and time.time() - last_change >= quiet:
                break

    def _run_native_command(self, command: str) -> str:
        if not self.server.is_server_running():
            return '服务端未运行（MCDR 尚未启动 MC 服务端）'
        command = command.lstrip('/')
        wait = float(self.config.get('native_output_wait', 2.0))
        self._capture.start(wait)
        try:
            self.server.execute(command)
        except Exception as e:  # noqa: BLE001
            self._capture.collect()
            return f'指令发送失败: {e}'
        self._wait_output_quiet(lambda: len(self._capture.collect()))
        lines = [_strip_color(l) for l in self._capture.collect()]
        return '\n'.join(lines)

    def on_info(self, server: PluginServerInterface, info):
        # 只收服务端 stdout 的行（玩家聊天 / 控制台输入走 user_info，不混入）
        if info.is_from_server:
            raw = info.raw_content or ''
            if raw:
                self._capture.feed(raw)


# ---------------- 插件入口 ----------------

bridge: RconBridge = None


def on_load(server: PluginServerInterface, prev_module):
    global bridge
    config = server.load_config_simple('config.json', DEFAULT_CONFIG)
    bridge = RconBridge(server, config)
    bridge.start()

    server.register_event_listener('mcdr.general_info', bridge.on_info)

    def print_status(src, ctx):
        enabled = config.get('enable', True)
        host = config.get('host', '127.0.0.1')
        port = config.get('port', 30166)
        listening = bridge._tcp_server is not None
        state = '运行中' if listening else '未运行'
        src.reply(f'RconBridge v{PLUGIN_VERSION} | {host}:{port} {state}'
                  + ('' if enabled else ' | 已在配置中禁用'))

    server.register_command(
        Literal('!!rconbridge').runs(print_status)
    )
    server.register_help_message(
        '!!rconbridge', '查看内置 RCON 服务端状态'
    )


def on_unload(server: PluginServerInterface):
    global bridge
    if bridge is not None:
        bridge.stop()
        bridge = None
