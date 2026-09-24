#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LunaShare 电脑端加密发送脚本 luna-send

将文件在本地用 AES-256-GCM 加密后，传到手机端 LunaShare，手机端自动解密还原为原文件
（文件名也一并加密，手机端还原为原名称）。加密口令与手机端共享配置里的「文件加密口令」必须一致。

大文件经内网穿透隧道（OpenFrp / mefrp）常因单条大请求超时被中断，故发送端把加密后的
整包 blob 切成多包（默认 512KB/包），逐包 POST 到 /_encupload_chunk（带会话/序号/总数），
单包失败可独立重试；手机端收齐所有分包后按序合并、再整体解密还原。整包单发路径 /_encupload 仍保留兼容。

断点续传：加密产物缓存到脚本目录 .luna_send_cache/<session>.blob，逐包确认状态记录在
luna-send-state.json（按文件绝对路径+大小+mtime 匹配）。传输中断后再次运行同一条 send 命令，
脚本先向手机端 POST /_encstatus 查询该会话已收到的分包序号，只补缺的包（服务端会话保留 24 小时）；
若上次其实已传完则直接跳过。传输成功后缓存与状态自动清理（缓存内容本身也是全程加密的）。

用法：
  luna-send config            # 交互配置服务器地址/账号/密码/加密口令（保存到脚本所在目录的 lunarc 文件，明文）
  luna-send send 文件1.zip 照片.jpg   # 加密分包发送（可同时多个，也可把文件直接拖入命令行窗口）
  luna-send encrypt 文件      # 仅本地加密、不发送，输出 文件.lunaenc（文件名与内容均已加密）
  luna-send test             # 测试与手机端服务器的连接

依赖：
  pip install cryptography
"""
import argparse
import base64
import json
import os
import sys
import time
import uuid
import urllib.parse
import urllib.request
import urllib.error

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.backends import default_backend
except ImportError:
    sys.exit("缺少依赖 cryptography，请先运行: pip install cryptography")

# 配置文件放在脚本所在目录的 lunarc（明文，自用方便，不限制权限）
CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lunarc")
PBKDF2_ITERS = 200_000      # 与手机端 FileServer 保持一致
SALT_LEN = 16
NONCE_LEN = 12
MAX_FILE_BYTES = 400 * 1024 * 1024   # 超过 400MB 不加密，避免内存压力
MAX_FILE_MB = MAX_FILE_BYTES // (1024 * 1024)

# 封装格式 v2（文件名也加密）：
#   nameLen(4 字节, 大端) || nameEnc(16 salt || 12 nonce || ct_name) || contentEnc(16 salt || 12 nonce || ct_content)
#   其中 ct_name / ct_content 末尾各含 16 字节 GCM tag。
#   与手机端 handleEncUpload 解析完全对应；旧格式（无 nameLen 前缀）手机端仍兼容（文件名走明文 header/query）。
HEADER_LEN = 4
CHUNK_SIZE = 512 * 1024           # 每包 512KB：包更小，经内网穿透隧道更不易整包出错，失败重传代价也小
CHUNK_TIMEOUT = 120               # 单包请求超时（秒）
CHUNK_RETRIES = 5                 # 单包失败重试次数（带退避）

# 断点续传：加密 blob 缓存目录 + 逐包确认状态文件（均在脚本所在目录；状态/缓存内容均为加密数据，不含明文）
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STATE_PATH = os.path.join(SCRIPT_DIR, "luna-send-state.json")
CACHE_DIR = os.path.join(SCRIPT_DIR, ".luna_send_cache")


def load_send_state():
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_send_state(state):
    try:
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False)
    except Exception:
        pass


def query_status(server, username, password, session):
    """查询服务端分包会话状态（POST /_encstatus，需 App 端为含此端点的版本）。
    返回 dict（{"done":true} / {"done":false,...,"received_idx":[...]} / {"unknown":true}）；
    服务端不支持或查询失败返回 None。"""
    url = f"{server.rstrip('/')}/_encstatus"
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    req = urllib.request.Request(url, data=b"", method="POST")
    req.add_header("Authorization", f"Basic {token}")
    req.add_header("X-Luna-Session", session)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))
    except Exception:
        return None


def load_config():
    if not os.path.exists(CONFIG_PATH):
        return {}
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    # 明文配置，自用场景不限制权限（如需保密可手动设为只读）


def derive_key(password, salt):
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=PBKDF2_ITERS,
        backend=default_backend(),
    )
    return kdf.derive(password.encode("utf-8"))


def encrypt_name(name, password):
    """加密文件名，返回 salt(16)||nonce(12)||ct(含 tag)。"""
    salt = os.urandom(SALT_LEN)
    nonce = os.urandom(NONCE_LEN)
    key = derive_key(password, salt)
    ct = AESGCM(key).encrypt(nonce, name.encode("utf-8"), None)
    return salt + nonce + ct


def encrypt_file(path, password):
    """加密文件内容 + 文件名，返回 v2 封装 blob。"""
    with open(path, "rb") as f:
        data = f.read()
    name = os.path.basename(path)
    name_enc = encrypt_name(name, password)
    salt = os.urandom(SALT_LEN)
    nonce = os.urandom(NONCE_LEN)
    key = derive_key(password, salt)
    ct = AESGCM(key).encrypt(nonce, data, None)
    name_len = len(name_enc).to_bytes(HEADER_LEN, "big")
    return name_len + name_enc + (salt + nonce + ct)


def send_chunk(server, username, password, session, index, total, chunk):
    """发送单个分包到 /_encupload_chunk，失败按退避重试（重试时打印进度日志）。返回 (http_code, text)。"""
    url = f"{server.rstrip('/')}/_encupload_chunk"
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    last_err = ""
    for attempt in range(1, CHUNK_RETRIES + 1):
        req = urllib.request.Request(url, data=chunk, method="POST")
        req.add_header("Authorization", f"Basic {token}")
        req.add_header("Content-Type", "application/octet-stream")
        req.add_header("X-Luna-Session", session)
        req.add_header("X-Luna-Chunk-Index", str(index))
        req.add_header("X-Luna-Chunk-Total", str(total))
        try:
            with urllib.request.urlopen(req, timeout=CHUNK_TIMEOUT) as resp:
                return resp.status, resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            last_err = f"HTTP {e.code}: {e.read().decode('utf-8', errors='replace')}"
            if e.code in (400, 403, 409):  # 不可逆错误，不重试
                return e.code, last_err
        except Exception as e:
            last_err = str(e)
        if attempt < CHUNK_RETRIES:
            delay = min(2 * attempt, 10)
            print(f"\n  [进度] 分包 {index} 第 {attempt}/{CHUNK_RETRIES} 次失败: {last_err}，{delay}s 后重试", flush=True)
            time.sleep(delay)
    return -1, last_err


def send_file(server, username, password, enc_password, filepath, on_progress=None):
    """加密整文件为 v2 blob，切成多包分别 POST /_encupload_chunk（带会话/序号/总数）。
    单包失败可独立重试，避免大文件经内网穿透隧道时单条大请求超时；收齐由服务端合并解密还原。
    文件名在加密 blob 内（v2 封装），请求 URL/头均不携带明文文件名。
    on_progress(sent_bytes, total_bytes, index, total_chunks, elapsed_s) 可选，用于显示进度。

    断点续传：blob 缓存 + 逐包确认状态落盘；重传时向服务端 /_encstatus 查询已收分包，
    只补缺的包；202 响应的 received_idx 为服务端真值，实时对齐。掉线后重跑同一命令即可续传。
    返回 (http_code, text, 耗时秒)。"""
    key = os.path.abspath(filepath)
    size = os.path.getsize(filepath)
    mtime = os.path.getmtime(filepath)
    state = load_send_state()
    st = state.get(key)
    blob = None
    session = None
    total = None
    confirmed = set()

    reusable = (isinstance(st, dict) and st.get("size") == size and st.get("mtime") == mtime
                and st.get("chunk_size") == CHUNK_SIZE and os.path.isfile(st.get("blob", "")))
    if reusable:
        session = st["session"]
        total = st["total"]
        with open(st["blob"], "rb") as f:
            blob = f.read()
        info = query_status(server, username, password, session)
        if info is None:
            # 服务端不支持状态查询（旧版本 App）或网络抖动：信任本地确认记录继续，后续两轮对账兜底
            confirmed = set(i for i in st.get("confirmed", []) if isinstance(i, int) and 0 <= i < total)
            print(f"  [进度] 断点续传: 本地记录已确认 {len(confirmed)}/{total} 包"
                  f"（服务端状态不可查，逐包对账）", flush=True)
        elif info.get("done"):
            print("  [进度] 检测到上次传输已完成（手机端已解密落盘），无需重传", flush=True)
            state.pop(key, None)
            save_send_state(state)
            try:
                os.remove(st["blob"])
            except OSError:
                pass
            return 201, '{"ok":true,"already_done":true}', 0.0
        elif info.get("total") == total and isinstance(info.get("received_idx"), list):
            confirmed = set(i for i in info["received_idx"] if isinstance(i, int) and 0 <= i < total)
            if len(confirmed) >= total:
                print(f"  [进度] 断点续传: 服务端已有 {total}/{total} 包（等待收齐合并）", flush=True)
            else:
                nxt = min(set(range(total)) - confirmed) + 1
                print(f"  [进度] 断点续传: 服务端已有 {len(confirmed)}/{total} 包，从第 {nxt} 包继续", flush=True)
        else:
            # 会话不存在（过期/从未开始）或分包总数不一致 → 服务端状态失效，从头传
            reusable = False
    if not reusable:
        if isinstance(st, dict) and os.path.isfile(st.get("blob", "")):
            try:
                os.remove(st["blob"])  # 清掉失效的旧缓存
            except OSError:
                pass
        t_enc = time.time()
        blob = encrypt_file(filepath, enc_password)
        enc_cost = time.time() - t_enc
        session = uuid.uuid4().hex
        total = max(1, (len(blob) + CHUNK_SIZE - 1) // CHUNK_SIZE)
        os.makedirs(CACHE_DIR, exist_ok=True)
        blob_path = os.path.join(CACHE_DIR, f"{session}.blob")
        with open(blob_path, "wb") as f:
            f.write(blob)
        state[key] = {"size": size, "mtime": mtime, "session": session, "total": total,
                      "chunk_size": CHUNK_SIZE, "blob": blob_path, "confirmed": [],
                      "updated_at": time.time()}
        save_send_state(state)
        print(f"  [进度] 加密完成: 密文 {len(blob)} B → {total} 包 "
              f"({CHUNK_SIZE // 1024}KB/包)，耗时 {enc_cost:.1f}s", flush=True)
    blob_len = len(blob)
    t_tx = time.time()

    def _persist():
        s2 = state.get(key)
        if isinstance(s2, dict):
            s2["confirmed"] = sorted(confirmed)
            s2["updated_at"] = time.time()
            save_send_state(state)

    def _notify(idx):
        if on_progress:
            sent = min(len(confirmed) * CHUNK_SIZE, blob_len)
            on_progress(sent, blob_len, idx, total, time.time() - t_tx)

    done = False
    # 第 1 轮：只发未确认的包；202 的 received_idx 是服务端真值，实时对齐并落盘
    # 第 2 轮兜底：未收到 201 收尾（服务端缺包/会话过期/收尾响应丢失）→ 按服务端真值（查不到则全量）重扫，
    # 同 blob 重发幂等；若服务端已收齐但未合并（收尾响应丢失），重发任一包即可触发收齐判定
    for rnd in (1, 2):
        if rnd == 2:
            info = query_status(server, username, password, session)
            if info and info.get("done"):
                done = True
                break
            if info and info.get("total") == total and isinstance(info.get("received_idx"), list):
                confirmed = set(i for i in info["received_idx"] if isinstance(i, int) and 0 <= i < total)
                _persist()
            elif info and info.get("unknown"):
                confirmed = set()  # 服务端会话已过期：本轮全量重发，重建会话传完
            pending = [i for i in range(total) if i not in confirmed]
            if not pending:
                pending = [0]  # 触发服务端「收齐→合并解密」判定
            print(f"  [进度] 第 2 轮对账: 服务端缺 {total - len(confirmed)} 包，补传中...", flush=True)
        else:
            pending = [i for i in range(total) if i not in confirmed]
        for i in pending:
            chunk = blob[i * CHUNK_SIZE:(i + 1) * CHUNK_SIZE]
            code, txt = send_chunk(server, username, password, session, i, total, chunk)
            if code == 201:
                confirmed.add(i)
                _notify(i)
                done = True
                break
            if code == 202:
                try:
                    rj = json.loads(txt)
                    if isinstance(rj.get("received_idx"), list):
                        confirmed = set(x for x in rj["received_idx"]
                                        if isinstance(x, int) and 0 <= x < total)
                    else:
                        confirmed.add(i)
                except Exception:
                    confirmed.add(i)
                _persist()
                _notify(i)
                continue
            # 不可逆失败（403/400/409 或重试耗尽）：保留状态，下次运行可从断点续传
            return code, txt, time.time() - t_tx
        if done:
            break
    if not done:
        return -1, "两轮发送后仍未收到服务端收齐确认（会话可能已过期），请重新运行命令重试", time.time() - t_tx
    # 服务端已收齐并解密落盘：清理状态与缓存
    state.pop(key, None)
    save_send_state(state)
    try:
        os.remove(os.path.join(CACHE_DIR, f"{session}.blob"))
    except OSError:
        pass
    return 201, json.dumps({"ok": True}), time.time() - t_tx


def cmd_config(args):
    cfg = load_config()
    print("=== LunaShare 脚本配置（直接回车沿用当前值）===")
    server = input(f"服务器地址 (当前: {cfg.get('server', '')}): ").strip() or cfg.get("server", "")
    username = input(f"账号 (当前: {cfg.get('username', '')}): ").strip() or cfg.get("username", "")
    pw = input(f"连接密码 (当前: {'*' * len(cfg.get('password', ''))}): ").strip()
    password = pw or cfg.get("password", "")
    enc = input(f"加密口令 (当前: {'*' * len(cfg.get('enc_password', ''))}): ").strip()
    enc_password = enc or cfg.get("enc_password", "")
    cfg.update(server=server, username=username, password=password, enc_password=enc_password)
    save_config(cfg)
    print(f"已保存到 {CONFIG_PATH}（明文，脚本同目录）")


def cmd_send(args):
    cfg = load_config()
    server = cfg.get("server") or sys.exit("未配置服务器地址，请先运行: luna-send config")
    username = cfg.get("username", "")
    password = cfg.get("password", "")
    enc_password = cfg.get("enc_password") or sys.exit(
        "未配置加密口令，请在手机端共享配置里点「生成加密口令并复制」，再填到本脚本配置"
    )
    for fp in args.files:
        if not os.path.isfile(fp):
            print(f"跳过(不存在): {fp}")
            continue
        if os.path.getsize(fp) > MAX_FILE_BYTES:
            mb = os.path.getsize(fp) // (1024 * 1024)
            print(f"跳过(超过 {MAX_FILE_MB}MB 限制，不加密): {fp} ({mb} MB)")
            continue
        size = os.path.getsize(fp)
        print(f"加密并分包发送: {fp} ({size} 字节) ...", flush=True)

        def _progress(sent, total_b, idx, total_c, elapsed):
            pct = (sent * 100 // total_b) if total_b else 100
            speed = (sent / (1024.0 * 1024.0) / elapsed) if elapsed > 0 else 0.0
            ts = time.strftime("%H:%M:%S")
            print(f"\r  [{ts}] 分包 [{idx + 1}/{total_c}] {pct}% "
                  f"({sent / (1024.0 * 1024.0):.1f}/{total_b / (1024.0 * 1024.0):.1f} MB) "
                  f"{speed:.2f} MB/s", end="", flush=True)

        code, txt, tx_cost = send_file(server, username, password, enc_password, fp, on_progress=_progress)
        if code in (200, 201):
            if "already_done" in txt:
                print(f"\r  [进度] 跳过（上次已传完，手机端已有该文件）" + " " * 30)
                continue
            speed = (size / (1024.0 * 1024.0) / tx_cost) if tx_cost > 0 else 0.0
            print(f"\r  [进度] 传输完成 (HTTP {code})：手机端已解密落盘，"
                  f"耗时 {tx_cost:.1f}s，平均 {speed:.2f} MB/s" + " " * 10)
        else:
            print(f"\r  失败 HTTP {code}: {txt}" + " " * 20)
            print("  已保存断点状态，排除故障后重新运行同一条命令即可从断点续传（24 小时内有效）", flush=True)


def cmd_test(args):
    cfg = load_config()
    server = cfg.get("server") or sys.exit("未配置服务器地址，请先运行: luna-send config")
    url = f"{server.rstrip('/')}/"
    req = urllib.request.Request(url, method="OPTIONS")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            print(f"连接正常 (HTTP {r.status})")
    except Exception as e:
        print(f"连接失败: {e}")


def cmd_encrypt(args):
    cfg = load_config()
    for fp in args.files:
        if not os.path.isfile(fp):
            print(f"跳过(不存在): {fp}")
            continue
        size = os.path.getsize(fp)
        if size > MAX_FILE_BYTES:
            mb = size // (1024 * 1024)
            print(f"跳过(超过 {MAX_FILE_MB}MB 限制，不加密): {fp} ({mb} MB)")
            continue
        # 口令来源优先级：命令行 --password > 同目录 lunarc 的 enc_password > 交互输入
        if args.password:
            enc_password = args.password
        elif cfg.get("enc_password"):
            enc_password = cfg["enc_password"]
        else:
            enc_password = input(f"加密口令 (用于本地加密，需与手机端共享「文件加密口令」一致): ").strip()
        if not enc_password:
            print("未提供加密口令，已跳过。")
            continue
        blob = encrypt_file(fp, enc_password)
        out_dir = args.out or os.path.dirname(fp) or "."
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, os.path.basename(fp) + ".lunaenc")
        with open(out_path, "wb") as f:
            f.write(blob)
        print(f"已加密(文件名+内容): {fp} ({size} 字节) -> {out_path} ({len(blob)} 字节)")
        print(f"       封装格式 v2: nameLen(4)||nameEnc||contentEnc，可直接 POST 给手机端 /_encupload 解密还原原文件名")


def main():
    p = argparse.ArgumentParser(description="LunaShare 电脑端加密发送脚本")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("config", help="交互配置连接信息").set_defaults(func=cmd_config)
    sp = sub.add_parser("send", help="加密发送文件（支持多个 / 拖入窗口）")
    sp.add_argument("files", nargs="+", help="要发送的文件路径")
    sp.set_defaults(func=cmd_send)
    ep = sub.add_parser("encrypt", help="仅本地加密文件、不发送（输出 文件.lunaenc）")
    ep.add_argument("files", nargs="+", help="要加密的文件路径")
    ep.add_argument("--password", help="加密口令（缺省读同目录 lunarc 或交互输入）")
    ep.add_argument("--out", help="输出目录（缺省为原文件同目录）")
    ep.set_defaults(func=cmd_encrypt)
    sub.add_parser("test", help="测试服务器连接").set_defaults(func=cmd_test)
    args = p.parse_args()
    if not getattr(args, "cmd", None):
        p.print_help()
        return
    args.func(args)


if __name__ == "__main__":
    main()
