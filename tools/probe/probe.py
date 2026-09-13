#!/usr/bin/env python3
"""Read-mostly protocol probe for Any Listen web-server v0.11.0-beta.1.

Secrets come from the environment. This script never prints the password,
token, or raw media URL. It does not delete server files.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote, urljoin, urlparse

HELLO_MSG = "Hello~::^-^::~v1~"
ID_PREFIX = "OjppZDo6-"
AUTH_FAILED = "Auth failed"
BLOCKED_IP = "Blocked IP"
VIRTUAL_PROTOCOL = "al-ps-host:"
P_STATIC_PREFIX = "/api/p_static/"
P_URL_PREFIX = "/api/p_url/"
PUBLIC_MEDIA_PREFIX = "/public/medias/"
ALLOWED_VIRTUAL_PREFIXES = (PUBLIC_MEDIA_PREFIX, P_STATIC_PREFIX, P_URL_PREFIX)
BUILT_IN_LISTS = ("default", "love", "last_played")

ROOT = Path(__file__).resolve().parents[2]


def redact(value: str) -> str:
    if not value:
        return ""
    if len(value) <= 8:
        return "***"
    return value[:4] + "…" + value[-2:]


def load_env_file() -> None:
    env_path = ROOT / ".env"
    if not env_path.is_file():
        return
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, val = line.split("=", 1)
        os.environ[key.strip()] = val.strip().strip('"').strip("'")


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def new_ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx


def object_keys(value: Any) -> list[str]:
    if isinstance(value, dict):
        return sorted(value.keys())
    return []


def path_kind(path: str) -> str:
    if path.startswith(PUBLIC_MEDIA_PREFIX):
        return "public_medias"
    if path.startswith(P_STATIC_PREFIX):
        return "p_static"
    if path.startswith(P_URL_PREFIX):
        return "p_url"
    if path.startswith("/api/"):
        return "api_other"
    return "external"


def allowed_virtual_path(path: str) -> bool:
    if path.startswith("//") or ".." in path:
        return False
    for prefix in ALLOWED_VIRTUAL_PREFIXES:
        if path.startswith(prefix):
            rest = path[len(prefix) :]
            return bool(rest) and "/" not in rest
    return False


def materialize_media_url(url: str, base_url: str) -> str:
    raw = url.strip()
    if raw.startswith(VIRTUAL_PROTOCOL):
        path = raw[len(VIRTUAL_PROTOCOL) :]
        if not allowed_virtual_path(path):
            return ""
        return urljoin(base_url, path)
    return urljoin(base_url, raw)


def classify_url(url: str, base_url: str) -> dict[str, Any]:
    virtual = url.startswith(VIRTUAL_PROTOCOL)
    absolute = materialize_media_url(url, base_url) if virtual else urljoin(base_url, url)
    parsed = urlparse(absolute)
    kind = path_kind(parsed.path or "")
    if virtual and not absolute:
        kind = "virtual_other"
    base_host = (urlparse(base_url).hostname or "").lower()
    host = (parsed.hostname or "").lower()
    return {
        "scheme": parsed.scheme or ("virtual" if virtual else ""),
        "kind": kind,
        "sameHost": bool(host) and host == base_host,
        "host": redact(host),
        "virtual": virtual,
        "absolute": absolute,
    }


class ProbeError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[override]
        return None


class MiniWebSocket:
    def __init__(self, url: str, ssl_context: ssl.SSLContext, timeout: float) -> None:
        parsed = urlparse(url)
        if parsed.scheme != "wss":
            raise ProbeError(3, "WebSocket URL must be wss")
        self.host = parsed.hostname or ""
        self.port = parsed.port or 443
        self.path = parsed.path or "/"
        if parsed.query:
            self.path += "?" + parsed.query
        self.ssl_context = ssl_context
        self.timeout = timeout
        self.sock: ssl.SSLSocket | None = None
        self.buf = bytearray()

    def connect(self) -> int:
        raw = socket.create_connection((self.host, self.port), self.timeout)
        sock = self.ssl_context.wrap_socket(raw, server_hostname=self.host)
        sock.settimeout(self.timeout)
        self.sock = sock
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        host_header = self.host if self.port == 443 else f"{self.host}:{self.port}"
        request = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {host_header}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"Origin: https://{host_header}\r\n"
            f"User-Agent: any-listen-android-probe/0.1\r\n"
            f"\r\n"
        )
        sock.sendall(request.encode("ascii"))
        header = self._read_http_headers()
        status_line = header.split(b"\r\n", 1)[0].decode("iso-8859-1", "replace")
        parts = status_line.split()
        try:
            return int(parts[1])
        except (IndexError, ValueError) as exc:
            raise ProbeError(4, "WebSocket handshake unreadable") from exc

    def send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode("utf-8"))

    def send_pong(self, payload: bytes = b"") -> None:
        self._send_frame(0xA, payload)

    def recv_text(self) -> str:
        while True:
            opcode, payload, fin = self._recv_frame()
            if opcode == 0x9:
                self.send_pong(payload)
                continue
            if opcode == 0xA:
                continue
            if opcode == 0x8:
                raise ProbeError(4, "WebSocket closed by server")
            if opcode != 0x1:
                continue
            data = bytearray(payload)
            while not fin:
                n_opcode, more, fin = self._recv_frame()
                if n_opcode not in (0x0, 0x1):
                    raise ProbeError(4, "unexpected WebSocket continuation")
                data.extend(more)
            return data.decode("utf-8", "replace")

    def close(self, code: int = 1000) -> None:
        if self.sock is None:
            return
        try:
            self._send_frame(0x8, struct.pack("!H", code))
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass
        self.sock = None

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        if self.sock is None:
            raise ProbeError(3, "WebSocket not connected")
        header = bytearray([0x80 | opcode])
        mask_bit = 0x80
        length = len(payload)
        if length < 126:
            header.append(mask_bit | length)
        elif length < 65536:
            header.append(mask_bit | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(mask_bit | 127)
            header.extend(struct.pack("!Q", length))
        mask = os.urandom(4)
        header.extend(mask)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + masked)

    def _recv_frame(self) -> tuple[int, bytes, bool]:
        first, second = self._recv_exact(2)
        fin = (first & 0x80) != 0
        opcode = first & 0x0F
        masked = (second & 0x80) != 0
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._recv_exact(8))[0]
        mask = self._recv_exact(4) if masked else b""
        payload = self._recv_exact(length)
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return opcode, payload, fin

    def _read_http_headers(self) -> bytes:
        while b"\r\n\r\n" not in self.buf:
            chunk = self._raw_recv()
            if not chunk:
                break
            self.buf.extend(chunk)
        idx = self.buf.find(b"\r\n\r\n")
        if idx < 0:
            preview = bytes(self.buf[:80]).decode("iso-8859-1", "replace")
            raise ProbeError(4, f"WebSocket handshake incomplete ({preview!r})")
        header = bytes(self.buf[: idx + 4])
        del self.buf[: idx + 4]
        return header

    def _recv_exact(self, size: int) -> bytes:
        while len(self.buf) < size:
            chunk = self._raw_recv()
            if not chunk:
                raise ProbeError(4, "WebSocket stream ended")
            self.buf.extend(chunk)
        data = bytes(self.buf[:size])
        del self.buf[:size]
        return data

    def _raw_recv(self) -> bytes:
        if self.sock is None:
            raise ProbeError(3, "WebSocket not connected")
        try:
            return self.sock.recv(4096)
        except TimeoutError as exc:
            raise ProbeError(4, "WebSocket timeout") from exc
        except ssl.SSLError as exc:
            raise ProbeError(3, "CERT_INVALID during WebSocket") from exc


class Probe:
    def __init__(self, base_url: str, timeout: float, out_dir: Path) -> None:
        self.base_url = base_url.rstrip("/") + "/"
        self.timeout = timeout
        self.out_dir = out_dir
        self.ssl_context = new_ssl_context()
        self.results: list[dict[str, Any]] = []
        https = urllib.request.HTTPSHandler(context=self.ssl_context)
        self.opener = urllib.request.build_opener(https)
        self.media_opener = urllib.request.build_opener(https, NoRedirect())

    def record(self, name: str, **fields: Any) -> None:
        row = {"name": name, **fields}
        self.results.append(row)
        print(f"[{row.get('status', '?')}] {name}: {row.get('detail', '')}")

    def request(
        self,
        method: str,
        path: str,
        headers: dict[str, str] | None = None,
        range_header: str | None = None,
        absolute: str | None = None,
        data: bytes | None = None,
        follow: bool = True,
        max_body: int = 2048,
    ) -> tuple[int, dict[str, str], bytes, list[str]]:
        url = absolute or urljoin(self.base_url, path.lstrip("/"))
        req_headers = {"User-Agent": "any-listen-android-probe/0.1"}
        if headers:
            req_headers.update(headers)
        if range_header:
            req_headers["Range"] = range_header
        request = urllib.request.Request(url, data=data, method=method, headers=req_headers)
        opener = self.opener if follow else self.media_opener
        try:
            with opener.open(request, timeout=self.timeout) as resp:
                body = resp.read(max_body)
                cookies = resp.headers.get_all("set-cookie") or []
                return resp.status, {k.lower(): v for k, v in resp.headers.items()}, body, cookies
        except urllib.error.HTTPError as exc:
            body = exc.read(max_body)
            cookies = exc.headers.get_all("set-cookie") if exc.headers else None
            return exc.code, {k.lower(): v for k, v in (exc.headers.items() if exc.headers else [])}, body, cookies or []
        except ssl.SSLError as exc:
            raise ProbeError(3, "CERT_INVALID") from exc
        except urllib.error.URLError as exc:
            reason = str(exc.reason)
            if "CERTIFICATE" in reason.upper() or "SSL" in reason.upper():
                raise ProbeError(3, "CERT_INVALID") from exc
            raise ProbeError(3, "NETWORK_UNREACHABLE") from exc

    def hello(self) -> None:
        code, _, body, _ = self.request("GET", "/api/ipc/hello")
        text = body.decode("utf-8", "replace")
        ok = code == 200 and text == HELLO_MSG
        self.record(
            "hello",
            status="pass" if ok else "fail",
            http=code,
            detail="hello constant matched" if ok else "unexpected hello body",
        )
        if not ok:
            raise ProbeError(4, "hello mismatch")

    def server_id(self) -> str:
        code, _, body, _ = self.request("GET", "/api/ipc/id")
        text = body.decode("utf-8", "replace")
        ok = code == 200 and text.startswith(ID_PREFIX) and len(text) > len(ID_PREFIX)
        server_id = text[len(ID_PREFIX) :] if ok else ""
        self.record(
            "id",
            status="pass" if ok else "fail",
            http=code,
            detail=f"serverId={redact(server_id)}" if ok else "id prefix mismatch",
        )
        if not ok:
            raise ProbeError(4, "id mismatch")
        return server_id

    def auth(self, password: str, expect_success: bool) -> str | None:
        salt = str(time.time_ns())
        digest = sha256_hex(password + salt)
        code, headers, body, _ = self.request(
            "POST",
            "/api/ipc/ah",
            headers={"m": digest, "s": salt},
        )
        text = body.decode("utf-8", "replace")
        token = headers.get("token")
        if expect_success:
            lines = text.split("\n")
            ok = code == 200 and lines and lines[0] == HELLO_MSG and bool(token)
            self.record(
                "auth_password",
                status="pass" if ok else "fail",
                http=code,
                detail="token issued" if ok else "password auth failed",
            )
            if not ok:
                raise ProbeError(5, "password auth failed")
            return token
        ok = code == 401 and AUTH_FAILED in text
        blocked = code == 403 and BLOCKED_IP in text
        self.record(
            "auth_wrong_password",
            status="pass" if ok or blocked else "fail",
            http=code,
            detail=AUTH_FAILED if ok else (BLOCKED_IP if blocked else "unexpected wrong-password response"),
        )
        if not (ok or blocked):
            raise ProbeError(4, "unexpected wrong-password response")
        return None

    def session(self, token: str) -> None:
        code, _, body, _ = self.request("POST", "/api/ipc/ah", headers={"m": token})
        text = body.decode("utf-8", "replace")
        ok = code == 200 and text.split("\n", 1)[0] == HELLO_MSG
        self.record(
            "auth_session",
            status="pass" if ok else "fail",
            http=code,
            detail="session restored" if ok else "session restore failed",
        )
        if not ok:
            raise ProbeError(5, "session restore failed")

    def ipc_call(self, ws: MiniWebSocket, pathname: list[str], args: list[Any] | None = None) -> Any:
        name = f"{pathname[-1]}_{uuid.uuid4().hex[:12]}"
        frame = [0, name, pathname, args or [], []]
        ws.send_text(json.dumps(frame, separators=(",", ":")))
        deadline = time.time() + self.timeout
        while time.time() < deadline:
            remaining = max(0.1, deadline - time.time())
            if ws.sock is not None:
                ws.sock.settimeout(remaining)
            raw = ws.recv_text()
            if raw == "ping":
                continue
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if not isinstance(data, list) or not data:
                continue
            if data[0] != 1 or len(data) < 3 or data[1] != name:
                continue
            err = data[2]
            if err not in (None,):
                message = err.get("message") if isinstance(err, dict) else "ipc error"
                raise ProbeError(4, f"{pathname[-1]} failed")
            return data[3] if len(data) > 3 else None
        raise ProbeError(4, f"{pathname[-1]} timeout")

    def websocket_flow(self, token: str, test_playlist_id: str, write_playlist: bool = False) -> None:
        ws_url = urljoin(self.base_url, f"api/ipc/socket?m={quote(token, safe='')}&t=main")
        parsed = urlparse(ws_url)
        ws_url = parsed._replace(scheme="wss").geturl()
        ws = MiniWebSocket(ws_url, self.ssl_context, self.timeout)
        try:
            status = ws.connect()
            ok = status == 101
            self.record(
                "ws",
                status="pass" if ok else "fail",
                http=status,
                detail="101 Switching Protocols" if ok else f"upgrade status {status}",
            )
            if not ok:
                raise ProbeError(4, "WebSocket unauthorized" if status == 401 else "WebSocket handshake failed")

            self.ipc_call(ws, ["inited"])
            self.record("inited", status="pass", detail="inited returned")

            version = self.ipc_call(ws, ["getCurrentVersionInfo"])
            version_name = ""
            if isinstance(version, dict):
                version_name = str(version.get("version") or "")
            self.record(
                "version",
                status="pass" if version_name else "fail",
                detail=f"version={version_name or 'missing'}",
                keys=object_keys(version),
            )

            lists = self.ipc_call(ws, ["getAllUserLists"])
            if not isinstance(lists, dict):
                self.record("lists", status="fail", detail="getAllUserLists did not return an object")
                raise ProbeError(4, "lists shape mismatch")
            user_lists = lists.get("userList") if isinstance(lists.get("userList"), list) else []
            built_in = [key for key in ("defaultList", "loveList", "lastPlayList") if isinstance(lists.get(key), dict)]
            built_in_ids = []
            for key, expected in (
                ("defaultList", "default"),
                ("loveList", "love"),
                ("lastPlayList", "last_played"),
            ):
                item = lists.get(key)
                if isinstance(item, dict) and item.get("id"):
                    built_in_ids.append(str(item["id"]))
                elif isinstance(item, dict):
                    built_in_ids.append(expected)
            user_ids = [str(item.get("id") or "") for item in user_lists if isinstance(item, dict)]
            test_present = bool(test_playlist_id) and test_playlist_id in user_ids
            self.record(
                "lists",
                status="pass",
                detail=f"builtIn={len(built_in)} userLists={len(user_lists)} testPlaylist={'yes' if test_present else 'no'}",
                keys=object_keys(lists),
                builtInIds=built_in_ids,
                testPlaylistPresent=test_present,
            )

            source_lists = [item for item in [*built_in_ids, *user_ids] if item != test_playlist_id]
            sample = self._first_music(ws, source_lists)
            if sample is None:
                self.record("tracks", status="fail", detail="no tracks in any list")
                self.record("media_url", status="skipped", detail="no track to resolve")
                self.record("range", status="skipped", detail="no media URL")
                if write_playlist:
                    self.record("playlist_write", status="skipped", detail="no source track for isolated write")
                return

            music, list_id = sample
            ids_first = self._music_ids(ws, list_id)
            ids_second = self._music_ids(ws, list_id)
            self.record(
                "tracks",
                status="pass",
                detail=f"count={len(ids_first)} stableIds={'yes' if ids_first == ids_second else 'no'}",
                musicKeys=object_keys(music),
                metaKeys=object_keys(music.get("meta") if isinstance(music.get("meta"), dict) else {}),
                hasInterval=isinstance(music.get("interval"), str),
                hasFingerprint=bool((music.get("meta") or {}).get("musicId")) if isinstance(music.get("meta"), dict) else False,
                idStableInSession=ids_first == ids_second,
            )

            payload = {"musicInfo": music, "isRefresh": False}
            media = self.ipc_call(ws, ["getMusicUrl"], [payload])
            url = media.get("url") if isinstance(media, dict) else ""
            if not isinstance(url, str) or not url:
                self.record("media_url", status="fail", detail="empty url")
                self.record("range", status="skipped", detail="no media URL")
                return
            info = classify_url(url, self.base_url)
            public = {k: v for k, v in info.items() if k != "absolute"}
            self.record(
                "media_url",
                status="pass" if info["kind"] in {"public_medias", "p_static", "p_url", "external"} and info["scheme"] in {"https", "http"} else "fail",
                detail=f"{info['kind']} {info['scheme']} virtual={info['virtual']} sameHost={info['sameHost']}",
                keys=object_keys(media),
                **public,
            )

            pic = self.ipc_call(ws, ["getMusicPic"], [{"musicInfo": music}])
            pic_url = pic.get("url") if isinstance(pic, dict) else ""
            self.record(
                "cover",
                status="pass" if isinstance(pic, dict) else "fail",
                detail="url present" if isinstance(pic_url, str) and pic_url else "no cover url",
                keys=object_keys(pic),
            )

            lyric = self.ipc_call(ws, ["getMusicLyric"], [{"musicInfo": music}])
            lyric_info = lyric.get("info") if isinstance(lyric, dict) else None
            has_lyric = isinstance(lyric_info, dict) and bool(lyric_info.get("lyric"))
            self.record(
                "lyric",
                status="pass" if isinstance(lyric, dict) else "fail",
                detail="lyric present" if has_lyric else "lyric empty",
                keys=object_keys(lyric),
                infoKeys=object_keys(lyric_info),
            )

            cookie = ""
            if info["kind"] == "p_url":
                cookie = self._proxy_cookie(token)
            elif info["kind"] in {"p_static", "public_medias"}:
                self.record(
                    "proxy_cookie",
                    status="skipped",
                    detail=f"{info['kind']} does not require p_urlkey",
                )
            target = info.get("absolute") or materialize_media_url(url, self.base_url)
            self._range_probe(target, cookie, info)
            if write_playlist:
                self._playlist_write(ws, test_playlist_id, user_ids, music)
        finally:
            ws.close(1000)

    def _playlist_write(
        self,
        ws: MiniWebSocket,
        test_id: str,
        user_ids: list[str],
        music: dict[str, Any],
    ) -> None:
        if test_id in BUILT_IN_LISTS:
            self.record("playlist_write", status="fail", detail="refusing to write a built-in list")
            return
        if test_id not in user_ids:
            self.record("playlist_write", status="fail", detail="test playlist is not a user list")
            return
        track_id = str(music.get("id") or "")
        if not track_id:
            self.record("playlist_write", status="skipped", detail="source track missing id")
            return
        before = self._music_ids(ws, test_id)
        self.ipc_call(
            ws,
            ["listAction"],
            [{
                "action": "list_music_add",
                "data": {
                    "id": test_id,
                    "musicInfos": [music],
                    "addMusicLocationType": "bottom",
                },
            }],
        )
        after_add = self._music_ids(ws, test_id)
        added = track_id in after_add
        self.record(
            "playlist_add",
            status="pass" if added else "fail",
            detail=f"added={'yes' if added else 'no'} before={len(before)} after={len(after_add)}",
        )
        if not added:
            return
        self.ipc_call(
            ws,
            ["listAction"],
            [{
                "action": "list_music_remove",
                "data": {
                    "listId": test_id,
                    "ids": [track_id],
                },
            }],
        )
        after_remove = self._music_ids(ws, test_id)
        restored = track_id not in after_remove and after_remove == before
        self.record(
            "playlist_remove",
            status="pass" if restored else "fail",
            detail=f"restored={'yes' if restored else 'no'} count={len(after_remove)}",
        )

    def _first_music(self, ws: MiniWebSocket, list_ids: list[str]) -> tuple[dict[str, Any], str] | None:
        seen: set[str] = set()
        for list_id in list_ids:
            if not list_id or list_id in seen:
                continue
            seen.add(list_id)
            musics = self.ipc_call(ws, ["getListMusics"], [list_id])
            if isinstance(musics, list):
                for item in musics:
                    if isinstance(item, dict) and item.get("id"):
                        return item, list_id
        return None

    def _music_ids(self, ws: MiniWebSocket, list_id: str) -> list[str]:
        musics = self.ipc_call(ws, ["getListMusics"], [list_id])
        if not isinstance(musics, list):
            return []
        return [str(item.get("id") or "") for item in musics if isinstance(item, dict)]

    def _proxy_cookie(self, token: str) -> str:
        code, _, _, cookies = self.request("GET", f"/api/proxyUrlToken?m={quote(token, safe='')}")
        cookie_header = ""
        for raw in cookies:
            name = raw.split(";", 1)[0].strip()
            if name.lower().startswith("p_urlkey="):
                cookie_header = name
                break
        if not cookie_header and cookies:
            cookie_header = cookies[0].split(";", 1)[0].strip()
        self.record(
            "proxy_cookie",
            status="pass" if code == 200 and cookie_header else "fail",
            http=code,
            detail="p_urlkey set" if "p_urlkey=" in cookie_header.lower() else "cookie missing",
        )
        return cookie_header

    def _range_probe(self, url: str, cookie: str, info: dict[str, Any]) -> None:
        if not url:
            self.record("range", status="fail", detail="could not materialize media URL")
            return
        if not url.startswith("https://"):
            self.record("range", status="fail", detail="media URL is not https")
            return
        headers = {}
        if cookie:
            headers["Cookie"] = cookie
        code, resp_headers, body, _ = self.request(
            "GET",
            "/",
            headers=headers,
            range_header="bytes=0-1023",
            absolute=url,
            follow=False,
            max_body=1024,
        )
        if code in {301, 302, 303, 307, 308}:
            location = resp_headers.get("location", "")
            loc_info = classify_url(location, self.base_url)
            self.record(
                "range_redirect",
                status="pass",
                http=code,
                detail=f"redirect {loc_info['kind']} {loc_info['scheme']}",
                **loc_info,
            )
            if loc_info.get("scheme") != "https":
                self.record("range", status="fail", detail="redirect target is not https")
                return
            code, resp_headers, body, _ = self.request(
                "GET",
                "/",
                range_header="bytes=0-1023",
                absolute=urljoin(url, location),
                follow=False,
                max_body=1024,
            )
        content_range = resp_headers.get("content-range", "")
        content_type = (resp_headers.get("content-type") or "").split(";")[0]
        accept_ranges = resp_headers.get("accept-ranges", "")
        if code == 206:
            status = "pass"
            detail = f"206 {content_type or 'unknown-type'} bytes={len(body)}"
        elif code == 200:
            status = "fail"
            detail = f"200 full response; Range ignored; bytes={len(body)}"
        else:
            status = "fail"
            detail = f"http {code}; bytes={len(body)}"
        self.record(
            "range",
            status=status,
            http=code,
            detail=detail,
            acceptRanges=accept_ranges,
            hasContentRange=bool(content_range),
            contentType=content_type,
            bodyBytes=len(body),
        )

    def write_report(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        host = urlparse(self.base_url).hostname or "unknown"
        payload = {
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "targetHostRedacted": redact(host),
            "serverVersionExpected": "0.11.0-beta.1",
            "results": self.results,
            "note": "Raw URLs, tokens, and titles are not written.",
        }
        path = self.out_dir / "report.json"
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"Wrote {path}")


def main() -> int:
    load_env_file()
    parser = argparse.ArgumentParser(description="Any Listen v0.11.0-beta.1 probe")
    parser.add_argument("--write-playlist", action="store_true", help="reserved; refuses unless test playlist id is set")
    args = parser.parse_args()

    base = os.environ.get("ANYLISTEN_BASE_URL", "").strip()
    if not base:
        print("Set ANYLISTEN_BASE_URL (see .env.example).", file=sys.stderr)
        return 2
    parsed = urlparse(base)
    if parsed.scheme != "https":
        print("ANYLISTEN_BASE_URL must be https.", file=sys.stderr)
        return 3

    timeout = float(os.environ.get("ANYLISTEN_TIMEOUT_SEC", "20"))
    password = os.environ.get("ANYLISTEN_PASSWORD", "")
    test_playlist_id = os.environ.get("ANYLISTEN_TEST_PLAYLIST_ID", "").strip()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / "captures" / "private" / f"probe-{stamp}"

    probe = Probe(base, timeout, out_dir)
    try:
        probe.hello()
        probe.server_id()
        token = None
        if password:
            probe.auth("definitely-wrong-password", expect_success=False)
            token = probe.auth(password, expect_success=True)
            if token:
                probe.session(token)
                probe.websocket_flow(token, test_playlist_id, write_playlist=args.write_playlist)
        else:
            probe.record(
                "auth_password",
                status="skipped",
                detail="ANYLISTEN_PASSWORD not set",
            )
        if args.write_playlist and not test_playlist_id:
            probe.record(
                "playlist_write",
                status="skipped",
                detail="refusing write without ANYLISTEN_TEST_PLAYLIST_ID",
            )
        probe.write_report()
    except ProbeError as exc:
        probe.record("fatal", status="fail", detail=str(exc))
        try:
            probe.write_report()
        except OSError:
            pass
        print(exc, file=sys.stderr)
        return exc.code
    return 0


if __name__ == "__main__":
    sys.exit(main())
