import ast
import contextlib
import hashlib
import html
import io
import json
import operator
import re
import time
import threading
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
from types import CodeType
from typing import Any
from urllib.parse import quote, urljoin, urlparse
from zoneinfo import ZoneInfo


SECRET_ASSIGNMENTS = {"USER_ID", "USER_PWD", "SENDER_EMAIL", "SENDER_PASS", "RECEIVER"}
ENGINE_LOCK = threading.Lock()
CAMPUS_FAILURE = threading.local()
CAS_BASE = "https://ids.gzist.edu.cn/lyuapServer"
PORTAL_BASE = "https://portal.gzist.edu.cn"
PORTAL_SERVICE = PORTAL_BASE
PORTAL_CAS_SERVICE = PORTAL_BASE + "/shiro-cas"
NOTICE_CARD_ID = "63dd2033655c42a0b150cbcc0164d4c5"
SCHEDULE_CARD_ID = "72fb0f5caef3425bbef9bd6096e780dc"
ACADEMIC_HOST = "jw.gzist.edu.cn"
ACADEMIC_CAS_SERVICE = f"http://{ACADEMIC_HOST}/sso/lyiotlogin"


class CampusRequestBlocked(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _stop_rejected_campus_response(response: Any, *args: Any, **kwargs: Any) -> Any:
    code = getattr(response, "status_code", 200)
    if code == 429:
        CAMPUS_FAILURE.code = "rate_limited"
        raise CampusRequestBlocked("rate_limited")
    if code == 403:
        CAMPUS_FAILURE.code = "access_denied"
        raise CampusRequestBlocked("access_denied")
    if code >= 500:
        CAMPUS_FAILURE.code = "server_error"
        raise CampusRequestBlocked("server_error")
    return response


def _evaluate_captcha_expression(expression: str, _globals: Any = None, _locals: Any = None) -> int | float:
    if not expression or len(expression) > 32:
        raise ValueError("invalid captcha expression")
    tree = ast.parse(expression, mode="eval")
    operators = {
        ast.Add: operator.add,
        ast.Sub: operator.sub,
        ast.Mult: operator.mul,
        ast.Div: operator.truediv,
        ast.FloorDiv: operator.floordiv,
    }

    def evaluate(node: ast.AST) -> int:
        if isinstance(node, ast.Expression):
            return evaluate(node.body)
        if isinstance(node, ast.Constant) and type(node.value) is int and abs(node.value) <= 10000:
            return node.value
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = evaluate(node.operand)
            return value if isinstance(node.op, ast.UAdd) else -value
        if isinstance(node, ast.BinOp) and type(node.op) in operators:
            left = evaluate(node.left)
            right = evaluate(node.right)
            if isinstance(node.op, (ast.Div, ast.FloorDiv)) and right == 0:
                raise ValueError("invalid captcha expression")
            value = operators[type(node.op)](left, right)
            if not isinstance(value, (int, float)) or abs(value) > 10000:
                raise ValueError("captcha result out of range")
            return int(value) if isinstance(value, float) and value.is_integer() else value
        raise ValueError("unsupported captcha expression")

    if sum(1 for _ in ast.walk(tree)) > 20:
        raise ValueError("captcha expression too complex")
    return evaluate(tree)


def _load_namespace(path: Path, portal_service: str | None = None) -> dict[str, Any]:
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path))
    filtered_nodes = []
    login_node = None
    captcha_node = None
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "login":
            login_node = node
        if isinstance(node, ast.FunctionDef) and node.name == "getCode":
            captcha_node = node
        if isinstance(node, (ast.Assign, ast.AnnAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            names = {target.id for target in targets if isinstance(target, ast.Name)}
            if names & SECRET_ASSIGNMENTS:
                continue
        filtered_nodes.append(node)
    tree.body = filtered_nodes

    if portal_service and login_node:
        for node in ast.walk(login_node):
            if isinstance(node, ast.Constant) and isinstance(node.value, str) and node.value.startswith("https://xsfw.gzist.edu.cn/"):
                node.value = portal_service
            elif isinstance(node, ast.Str) and node.s.startswith("https://xsfw.gzist.edu.cn/"):
                node.s = portal_service
    if captcha_node:
        for node in ast.walk(captcha_node):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "eval":
                node.func = ast.Name(id="_evaluate_captcha_expression", ctx=ast.Load())
    ast.fix_missing_locations(tree)
    namespace: dict[str, Any] = {
        "__name__": "dormcheck_engine",
        "__file__": str(path),
        "_evaluate_captcha_expression": _evaluate_captcha_expression,
    }
    code: CodeType = compile(tree, str(path), "exec")
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        exec(code, namespace)
    original_init = namespace.get("init")
    if callable(original_init):
        def guarded_init() -> Any:
            session = original_init()
            hooks = getattr(session, "hooks", None)
            if isinstance(hooks, dict):
                hooks.setdefault("response", []).insert(0, _stop_rejected_campus_response)
            return session
        namespace["init"] = guarded_init
    return namespace


def _safe_get(session: Any, url: str, **kwargs: Any) -> Any:
    for _ in range(5):
        response = session.get(url, allow_redirects=False, timeout=20, **kwargs)
        if response.status_code not in (301, 302, 303, 307, 308):
            return response
        location = response.headers.get("Location", "")
        target = urljoin(url, location)
        parsed = urlparse(target)
        if parsed.scheme != "https" or parsed.hostname not in {"ids.gzist.edu.cn", "portal.gzist.edu.cn"}:
            raise RuntimeError("unsafe campus redirect")
        url = target
    raise RuntimeError("too many campus redirects")


def _safe_campus_request(session: Any, method: str, url: str, **kwargs: Any) -> Any:
    """Follow only the allowlisted campus hosts used by the academic flow."""
    for _ in range(5):
        response = session.request(method, url, allow_redirects=False, **kwargs)
        if response.status_code not in (301, 302, 303, 307, 308):
            return response
        target = urljoin(url, response.headers.get("Location", ""))
        parsed = urlparse(target)
        if parsed.hostname not in {"ids.gzist.edu.cn", "portal.gzist.edu.cn", "jw.gzist.edu.cn"}:
            raise RuntimeError("unsafe campus redirect")
        if parsed.hostname != "jw.gzist.edu.cn" and parsed.scheme != "https":
            raise RuntimeError("unsafe campus redirect")
        url = target
    raise RuntimeError("too many campus redirects")


def _first_value(payload: Any, names: set[str]) -> str | None:
    if isinstance(payload, dict):
        for key, value in payload.items():
            if key in names and isinstance(value, (str, int)) and str(value):
                return str(value)
        for value in payload.values():
            found = _first_value(value, names)
            if found:
                return found
    elif isinstance(payload, list):
        for value in payload:
            found = _first_value(value, names)
            if found:
                return found
    return None


def _portal_user(payload: Any, session: Any) -> dict[str, str]:
    token = _cookie_value(session, "Authorization", host=urlparse(PORTAL_BASE).hostname, path="/")
    if not token:
        token = _first_value(payload, {"accessToken", "access_token", "token"})
    user_id = _first_value(payload, {"userId", "user_id", "loginUserId"})
    department_id = _first_value(payload, {"departmentId", "deptId", "loginUserDeptId"})
    org_id = _first_value(payload, {"orgId", "organizationId", "loginUserOrgId"}) or "-1"
    if not user_id and token:
        try:
            import base64
            claims = token.split(".")[1]
            claims += "=" * (-len(claims) % 4)
            decoded = json.loads(base64.urlsafe_b64decode(claims))
            user_id = str(decoded.get("sub") or "") or None
        except Exception:
            user_id = None
    if not token or not user_id or not department_id:
        raise RuntimeError("portal user context unavailable")
    return {"token": token, "user_id": user_id, "department_id": department_id, "org_id": org_id}


def _cookie_value(session: Any, name: str, host: str | None = None, path: str | None = None) -> str | None:
    """Read a cookie without requests raising when multiple scopes share a name."""
    matches = []
    for cookie in session.cookies:
        if cookie.name != name:
            continue
        domain = (cookie.domain or "").lstrip(".").lower()
        requested_host = host.lower() if host else ""
        if requested_host and domain and not (requested_host == domain or requested_host.endswith("." + domain)):
            continue
        cookie_path = cookie.path or "/"
        if path and not path.startswith(cookie_path):
            continue
        matches.append(cookie)
    if not matches:
        return None
    matches.sort(key=lambda cookie: len(cookie.path or "/"), reverse=True)
    return str(matches[0].value)


def _keep_most_specific_cookie(session: Any, name: str, url: str) -> str | None:
    """Keep one cookie of a name that applies to URL, preferring the narrowest scope."""
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    request_path = parsed.path or "/"
    matches = []
    for cookie in session.cookies:
        if cookie.name != name:
            continue
        domain = (cookie.domain or "").lstrip(".").lower()
        domain_matches = not domain or host == domain or host.endswith("." + domain)
        path = cookie.path or "/"
        path_matches = request_path.startswith(path) and (path.endswith("/") or len(request_path) == len(path) or request_path[len(path):].startswith("/"))
        secure_matches = not cookie.secure or parsed.scheme == "https"
        if domain_matches and path_matches and secure_matches:
            domain_specificity = len(domain) if getattr(cookie, "domain_specified", False) else len(host) + 1
            matches.append((len(path), domain_specificity, cookie))
    if not matches:
        return None
    matches.sort(key=lambda match: (match[0], match[1]), reverse=True)
    selected = matches[0][2]
    for _, _, cookie in matches[1:]:
        session.cookies.clear(cookie.domain, cookie.path, cookie.name)
    return str(selected.value)


def _parse_notice_items(payload: Any) -> list[dict[str, str]]:
    def looks_like_notice_rows(value: Any) -> bool:
        return isinstance(value, list) and any(
            isinstance(row, dict) and {"BT", "CJSJ", "URL"}.issubset(row.keys())
            for row in value
        )

    def find_rows(value: Any) -> Any:
        if isinstance(value, dict):
            content = value.get("contentDisplay")
            if isinstance(content, dict):
                # The portal returns card metadata in `contentDisplay` and the
                # actual rows in its sibling `data` field.
                raw = value.get("data")
                if raw is content or raw is None:
                    raw = content.get("data")
                if isinstance(raw, str):
                    try:
                        raw = json.loads(raw)
                    except (TypeError, ValueError):
                        raw = None
                if isinstance(raw, list) and not raw:
                    return raw
                if looks_like_notice_rows(raw):
                    return raw
                nested = find_rows(raw)
                if nested is not None:
                    return nested
            for child in value.values():
                found = find_rows(child)
                if found is not None:
                    return found
        elif isinstance(value, list):
            if looks_like_notice_rows(value):
                return value
            for child in value:
                found = find_rows(child)
                if found is not None:
                    return found
        elif isinstance(value, str) and value[:1] in {"{", "["}:
            try:
                return find_rows(json.loads(value))
            except (TypeError, ValueError, RecursionError):
                return None
        return None

    rows = find_rows(payload)
    if rows is None:
        raise RuntimeError("portal notice response invalid")
    result = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        title = row.get("BT")
        date = row.get("CJSJ")
        link = row.get("URL")
        if not all(isinstance(value, str) and value.strip() for value in (title, date, link)):
            raise RuntimeError("portal notice row incomplete")
        parsed = urlparse(link)
        if parsed.scheme != "https" or parsed.hostname != "jw.gzist.edu.cn":
            continue
        result.append({"title": title.strip()[:300], "date": date.strip()[:32], "url": link})
    return result[:10]


def _parse_schedule_items(payload: Any) -> list[dict[str, str]]:
    """Normalize the portal timetable into safe, display-oriented fields."""
    aliases = {
        "course": ("kcmc", "kcMc", "courseName", "course_name", "课程名称", "课程"),
        "teacher": ("xm", "jsxm", "teacher", "teacherName", "授课教师", "教师"),
        "room": ("cdmc", "jsmc", "room", "roomName", "教室", "上课地点"),
        "weekday": ("xqjmc", "xqj", "weekday", "weekDay", "day", "星期"),
        "periods": ("jcs", "jc", "jcxx", "period", "periods", "节次"),
        "weeks": ("zcd", "zc", "week", "weeks", "周次"),
        "type": ("kclbmc", "kclb", "kcxz", "kclx", "courseType", "课程性质"),
    }

    def decode(value: Any) -> Any:
        if isinstance(value, str) and value[:1] in {"{", "["}:
            try:
                return decode(json.loads(value))
            except (TypeError, ValueError, RecursionError):
                return value
        if isinstance(value, dict):
            return {key: decode(child) for key, child in value.items()}
        if isinstance(value, list):
            return [decode(child) for child in value]
        return value

    def value_for(row: dict[str, Any], names: tuple[str, ...]) -> str:
        for name in names:
            value = row.get(name)
            if isinstance(value, (str, int, float)) and str(value).strip():
                return html.unescape(str(value).strip())[:160]
        return ""

    def is_course_row(row: Any) -> bool:
        if not isinstance(row, dict) or not value_for(row, aliases["course"]):
            return False
        # `sjkList` is a separate practice-course summary and does not have
        # timetable coordinates. Prefer the concrete `kbList` rows instead.
        return any(value_for(row, aliases[name]) for name in ("weekday", "periods", "room", "weeks"))

    def find_rows(value: Any) -> list[dict[str, Any]] | None:
        if isinstance(value, dict) and isinstance(value.get("kbList"), list):
            rows = [row for row in value["kbList"] if isinstance(row, dict)]
            return rows
        if isinstance(value, list):
            rows = [row for row in value if isinstance(row, dict)]
            if rows and any(is_course_row(row) for row in rows):
                return rows
            for child in value:
                found = find_rows(child)
                if found is not None:
                    return found
        elif isinstance(value, dict):
            for child in value.values():
                found = find_rows(child)
                if found is not None:
                    return found
        return None

    rows = find_rows(decode(payload))
    if rows is None:
        raise RuntimeError("portal schedule response invalid")
    result = []
    for row in rows:
        if not isinstance(row, dict) or not value_for(row, aliases["course"]):
            continue
        course = value_for(row, aliases["course"])
        weekday = value_for(row, aliases["weekday"])
        periods = value_for(row, aliases["periods"])
        day_match = re.search(r"[1-7]", weekday)
        day_number = int(day_match.group()) if day_match else None
        if not day_match:
            day_names = {"一": "1", "二": "2", "三": "3", "四": "4", "五": "5", "六": "6", "日": "7", "天": "7"}
            day_match = re.search(r"[一二三四五六日天]", weekday)
            if day_match:
                day_number = int(day_names[day_match.group()])
        period_values = [int(value) for value in re.findall(r"\d+", periods)]
        if day_number is None or not period_values or not 1 <= day_number <= 7:
            raise RuntimeError("portal schedule row incomplete")
        start, end = period_values[0], period_values[-1]
        if not (1 <= start <= end <= 11):
            raise RuntimeError("portal schedule periods invalid")
        result.append({
            "course": course,
            "teacher": value_for(row, aliases["teacher"]),
            "room": value_for(row, aliases["room"]),
            "weekday": weekday,
            "periods": periods,
            "weeks": value_for(row, aliases["weeks"]),
            "type": value_for(row, aliases["type"]),
        })
    if len(result) > 200:
        raise RuntimeError("portal schedule row limit exceeded")
    return result


def _parse_academic_calendar_range(source: str) -> tuple[str, str]:
    """Read the official semester date range from the academic calendar HTML."""
    class TextExtractor(HTMLParser):
        def __init__(self) -> None:
            super().__init__(convert_charrefs=True)
            self.parts: list[str] = []

        def handle_data(self, data: str) -> None:
            self.parts.append(data)

    parser = TextExtractor()
    parser.feed(source)
    text = " ".join(parser.parts)
    match = re.search(r"(20\d{2}-\d{2}-\d{2})\s*至\s*(20\d{2}-\d{2}-\d{2})", text)
    if not match:
        raise RuntimeError("academic calendar date range missing")
    start, end = match.groups()
    start_date = datetime.strptime(start, "%Y-%m-%d").date()
    end_date = datetime.strptime(end, "%Y-%m-%d").date()
    if start_date > end_date or (end_date - start_date).days > 240:
        raise RuntimeError("academic calendar date range invalid")
    return start, end


def _safe_shape(value: Any, depth: int = 0) -> Any:
    if depth > 3:
        return type(value).__name__
    if isinstance(value, dict):
        return {str(key)[:40]: _safe_shape(child, depth + 1) for key, child in list(value.items())[:20]}
    if isinstance(value, list):
        return {"type": "list", "length": len(value), "item": _safe_shape(value[0], depth + 1) if value else None}
    return type(value).__name__


def _portal_login_failure_message(error: Any) -> str:
    code = str(error or "").upper()
    if "CODEFALSE" in code or "验证码错误" in code:
        return "统一认证验证码识别未通过，请稍后重试。"
    if "PASSERROR" in code or "密码错误" in code:
        return "统一认证平台拒绝了账号密码，请重新检查校园账号设置。"
    if "NOUSER" in code or "账号不存在" in code:
        return "统一认证平台未找到该校园账号，请重新检查校园账号设置。"
    return "统一认证未签发门户登录票据，可能是门户回调地址未被认证中心接受或登录状态异常。"


def _normalize_ticket(value: Any) -> str:
    """Normalize the CAS ticket without exposing or accepting arbitrary values."""
    if isinstance(value, bytes):
        value = value.decode("utf-8", "ignore")
    if isinstance(value, dict):
        value = _first_value(value, {"ticket", "tgt", "value", "data"}) or ""
    text = str(value or "").strip().strip('"').strip()
    if text[:1] in {"{", "["}:
        try:
            return _normalize_ticket(json.loads(text))
        except (TypeError, ValueError, RecursionError):
            return ""
    return text


def _is_safe_cas_ticket_candidate(ticket: str) -> bool:
    return bool(re.fullmatch(r"[A-Za-z0-9._~-]{8,200}", ticket))


def _portal_authenticated_session(
    path: Path,
    account: str,
    password: str,
    service: str = PORTAL_CAS_SERVICE,
) -> tuple[Any, dict[str, str], list[str]]:
    """Create a portal session and return only the safe request context."""
    diagnostic = ["start"]
    namespace = _load_namespace(path, portal_service=service)
    namespace["USER_ID"] = account
    namespace["USER_PWD"] = password
    captcha_state = {"image": "not_used", "ocr": "not_used"}
    original_get_code = namespace.get("getCode")
    if callable(original_get_code):
        def observed_get_code(image: str) -> Any:
            captcha_state["image"] = "present" if image else "empty"
            captcha_state["ocr"] = "invoked"
            result = original_get_code(image)
            captcha_state["ocr"] = "result_present" if result is not None else "result_empty"
            return result
        namespace["getCode"] = observed_get_code
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        session = namespace["init"]()
        ticket, login_error = namespace["login"](session)
    diagnostic.extend((f"captcha_image={captcha_state['image']}", f"ocr={captcha_state['ocr']}"))
    ticket = _normalize_ticket(ticket)
    if not ticket:
        diagnostic.append("login=" + ("captcha" if "CODEFALSE" in str(login_error).upper() or "验证码" in str(login_error) else "rejected"))
        raise RuntimeError(_portal_login_failure_message(login_error))
    if not _is_safe_cas_ticket_candidate(ticket):
        diagnostic.append(f"ticket=unsafe_shape_{type(ticket).__name__}_{len(ticket)}")
        raise RuntimeError("统一认证返回的登录票据格式异常，请稍后重试。")
    diagnostic.append("ticket=candidate_safe")
    if ticket.startswith("ST-"):
        service_ticket = ticket
        diagnostic.append("ticket_kind=service")
    else:
        diagnostic.append("ticket_kind=ticket_grant")
        ticket_response = session.post(
            f"{CAS_BASE}/v1/tickets/{quote(ticket, safe='')}",
            data={"service": service, "loginToken": "loginToken"},
            timeout=20, allow_redirects=False,
        )
        if ticket_response.status_code not in (200, 201):
            diagnostic.append(f"service_ticket=http_{ticket_response.status_code}")
            raise RuntimeError("校园统一认证未完成，请重新连接。")
        service_ticket = ticket_response.text.strip().strip('"')
        if not re.fullmatch(r"ST-[A-Za-z0-9-]{8,200}", service_ticket):
            try:
                service_ticket = str(ticket_response.json().get("ticket") or ticket_response.json().get("data") or "")
            except Exception:
                service_ticket = ""
        if not re.fullmatch(r"ST-[A-Za-z0-9-]{8,200}", service_ticket):
            diagnostic.append("service_ticket=invalid")
            raise RuntimeError("门户授权票据无效，请重新连接。")
    diagnostic.append("service_ticket=ok")
    service_query = quote(service_ticket, safe="")

    # The ZF academic system has its own CAS callback and session cookie.  It
    # cannot consume the portal's Authorization/customsid pair, so return as
    # soon as that callback establishes the academic JSESSIONID.
    if service == ACADEMIC_CAS_SERVICE:
        callback = _safe_campus_request(
            session,
            "GET",
            f"{service}?ticket={service_query}",
            headers={"Referer": f"{PORTAL_BASE}/", "User-Agent": "Mozilla/5.0"},
            timeout=20,
        )
        callback_host = urlparse(callback.url).hostname or "unknown"
        diagnostic.append(
            f"academic_callback_http_{callback.status_code}_host_{callback_host}_"
            f"cookies_{','.join(sorted(session.cookies.keys()))}"
        )
        if callback.status_code != 200 or callback_host != ACADEMIC_HOST:
            raise RuntimeError("教务系统登录未完成，请稍后重试。")
        if not _cookie_value(session, "JSESSIONID", host=ACADEMIC_HOST, path="/jwglxt"):
            diagnostic.append("academic_callback=missing_session")
            raise RuntimeError("教务系统登录未完成，请稍后重试。")
        academic_cookie = _keep_most_specific_cookie(
            session, "JSESSIONID", f"http://{ACADEMIC_HOST}/jwglxt/"
        )
        if not academic_cookie:
            diagnostic.append("academic_callback=invalid_session_scope")
            raise RuntimeError("教务系统登录未完成，请稍后重试。")
        diagnostic.append("academic_cookie=normalized")
        diagnostic.append("academic_callback=ok")
        return session, {}, diagnostic

    page = _safe_get(session, f"{PORTAL_BASE}/?ticket={service_query}")
    if not session.cookies:
        cas_page = _safe_get(session, f"{PORTAL_BASE}/shiro-cas?ticket={service_query}")
        if session.cookies:
            page = cas_page
            diagnostic.append("callback_fallback=shiro-cas")
    diagnostic.append(f"callback_http_{page.status_code}_cookies=" + ",".join(sorted(session.cookies.keys())))
    if page.status_code != 200:
        raise RuntimeError("无法建立融合门户会话。")
    portal_home = _safe_get(session, PORTAL_BASE + "/")
    app_match = re.search(r"(?:src|href)=(?:[\"'])?([^\"' >]*/assets/js/app\.[A-Za-z0-9_-]+\.js(?:\?[^\"' >]*)?)(?:[\"'])?", portal_home.text)
    if portal_home.status_code != 200 or not app_match:
        diagnostic.append(f"portal_bootstrap=failed_http_{portal_home.status_code}")
        raise RuntimeError("无法初始化门户安全校验。")
    app_bundle = _safe_get(session, urljoin(PORTAL_BASE + "/", app_match.group(1)))
    key_match = re.search(r"GATEWAY_KEY\s*:\s*['\"]([^'\"]{8,256})['\"]", app_bundle.text)
    if app_bundle.status_code != 200 or not key_match:
        diagnostic.append(f"gateway_key=missing_http_{app_bundle.status_code}")
        raise RuntimeError("无法初始化门户安全校验。")
    diagnostic.append("gateway_key=ok")
    timestamp = str(int(time.time() * 1000))
    csrf = hashlib.md5(f"timestamp={timestamp},key={key_match.group(1)}".encode()).hexdigest()
    info_headers = {
        "Authorization": "undefined", "Origin": PORTAL_BASE, "Referer": f"{PORTAL_BASE}/",
        "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded;charset=utf-8",
        "gatewayAppId": "ly-upp", "csrfTimestamp": timestamp, "csrfToken": csrf,
    }
    info_response = session.post(f"{PORTAL_BASE}/tryLoginUserInfo", data=b"", headers=info_headers, timeout=20, allow_redirects=False)
    if info_response.status_code != 200:
        raise RuntimeError("融合门户登录未完成，请稍后重试。")
    try:
        info_payload = info_response.json()
    except ValueError:
        raise RuntimeError("融合门户返回了无法识别的登录结果。")
    if not info_payload.get("meta", {}).get("success", False):
        raise RuntimeError("融合门户登录未完成，请稍后重试。")
    portal_user = _portal_user(info_payload, session)
    diagnostic.append("portal_user=ok")
    timestamp = str(int(time.time() * 1000))
    csrf = hashlib.md5(f"timestamp={timestamp},key={key_match.group(1)}".encode()).hexdigest()
    headers = {
        "Authorization": portal_user["token"], "Origin": PORTAL_BASE, "Referer": f"{PORTAL_BASE}/",
        "X-Requested-With": "XMLHttpRequest", "loginUserId": portal_user["user_id"],
        "loginUserName": portal_user["user_id"], "loginUserDeptId": portal_user["department_id"],
        "loginUserOrgId": portal_user["org_id"], "csrfTimestamp": timestamp, "csrfToken": csrf,
    }
    return session, headers, diagnostic


def fetch_portal_notices(path: Path, account: str, password: str) -> tuple[bool, list[dict[str, str]], str, list[str]]:
    """Fetch notices and return a short, secret-free diagnostic for server logs."""
    started = time.monotonic()
    diagnostic = ["start"]
    try:
        with ENGINE_LOCK:
            # The CAS login must issue a ticket for the portal's registered
            # callback service. The resulting service ticket is then consumed
            # by the portal root callback below.
            namespace = _load_namespace(path, portal_service=PORTAL_CAS_SERVICE)
            namespace["USER_ID"] = account
            namespace["USER_PWD"] = password
            captcha_state = {"image": "not_used", "ocr": "not_used"}
            original_get_code = namespace.get("getCode")
            if callable(original_get_code):
                def observed_get_code(image: str) -> Any:
                    captcha_state["image"] = "present" if image else "empty"
                    captcha_state["ocr"] = "invoked"
                    result = original_get_code(image)
                    captcha_state["ocr"] = "result_present" if result is not None else "result_empty"
                    return result

                namespace["getCode"] = observed_get_code
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                session = namespace["init"]()
                ticket, login_error = namespace["login"](session)
            output.close()
            diagnostic.extend((f"captcha_image={captcha_state['image']}", f"ocr={captcha_state['ocr']}"))
            ticket = _normalize_ticket(ticket)
            if not ticket:
                diagnostic.append("login=" + ("captcha" if "CODEFALSE" in str(login_error).upper() or "验证码" in str(login_error) else "rejected"))
                return False, [], _portal_login_failure_message(login_error), diagnostic
            # The CAS deployment may return a ticket without the traditional TGT- prefix.
            # Only pass a bounded URL-safe candidate to CAS; the ticket-exchange endpoint
            # remains the authority that decides whether it is valid.
            if not _is_safe_cas_ticket_candidate(ticket):
                diagnostic.append(f"ticket=unsafe_shape_{type(ticket).__name__}_{len(ticket)}")
                return False, [], "统一认证返回的登录票据格式异常，请稍后重试。", diagnostic
            diagnostic.append("ticket=candidate_safe")
            if ticket.startswith("ST-"):
                # Some CAS deployments return a service ticket directly from login.
                service_ticket = ticket
                diagnostic.append("ticket_kind=service")
            else:
                diagnostic.append("ticket_kind=ticket_grant")
                ticket_response = session.post(
                    f"{CAS_BASE}/v1/tickets/{quote(ticket, safe='')}",
                    data={"service": PORTAL_CAS_SERVICE, "loginToken": "loginToken"},
                    timeout=20,
                    allow_redirects=False,
                )
                if ticket_response.status_code not in (200, 201):
                    diagnostic.append(f"service_ticket=http_{ticket_response.status_code}")
                    return False, [], "校园统一认证未完成，请重新连接。", diagnostic
                service_ticket = ticket_response.text.strip().strip('"')
                if not re.fullmatch(r"ST-[A-Za-z0-9-]{8,200}", service_ticket):
                    try:
                        service_ticket = str(ticket_response.json().get("ticket") or ticket_response.json().get("data") or "")
                    except Exception:
                        service_ticket = ""
                if not re.fullmatch(r"ST-[A-Za-z0-9-]{8,200}", service_ticket):
                    diagnostic.append("service_ticket=invalid")
                    return False, [], "门户授权票据无效，请重新连接。", diagnostic
            diagnostic.append("service_ticket=ok")

            # This portal consumes the service ticket on its root callback. The
            # browser flow lands at /?ticket=... before bootstrapping the API.
            callback_url = f"{PORTAL_BASE}/?ticket={quote(service_ticket, safe='')}"
            page = _safe_get(session, callback_url)
            if not session.cookies:
                # Some deployments expose the ticket landing page at / but only
                # create the portal session on the registered CAS callback.
                cas_callback_url = f"{PORTAL_BASE}/shiro-cas?ticket={quote(service_ticket, safe='')}"
                cas_page = _safe_get(session, cas_callback_url)
                if session.cookies:
                    page = cas_page
                    callback_url = cas_callback_url
                    diagnostic.append("callback_fallback=shiro-cas")
            diagnostic.append(
                f"callback_http_{page.status_code}_cookies=" + ",".join(sorted(session.cookies.keys()))
                + f"_url_{urlparse(page.url).path or '/'}"
            )
            if page.status_code != 200:
                diagnostic.append(f"callback=http_{page.status_code}")
                return False, [], "无法建立融合门户会话。", diagnostic

            portal_home = _safe_get(session, PORTAL_BASE + "/")
            # The portal currently serves app.a5e.js; older builds used a longer hash.
            # The portal emits both quoted and unquoted HTML attributes.
            app_match = re.search(
                r"(?:src|href)=(?:[\"'])?([^\"' >]*/assets/js/app\.[A-Za-z0-9_-]+\.js(?:\?[^\"' >]*)?)(?:[\"'])?",
                portal_home.text,
            )
            if portal_home.status_code != 200 or not app_match:
                has_app_asset = "/assets/js/app" in portal_home.text
                diagnostic.append(
                    f"portal_bootstrap=failed_http_{portal_home.status_code}_len_{len(portal_home.text)}_"
                    f"has_app_{str(has_app_asset).lower()}"
                )
                return False, [], "无法初始化门户安全校验。", diagnostic
            app_url = urljoin(PORTAL_BASE + "/", app_match.group(1))
            if urlparse(app_url).hostname != "portal.gzist.edu.cn":
                return False, [], "无法初始化门户安全校验。"
            app_bundle = _safe_get(session, app_url)
            key_match = re.search(r"GATEWAY_KEY\s*:\s*['\"]([^'\"]{8,256})['\"]", app_bundle.text)
            if app_bundle.status_code != 200 or not key_match:
                diagnostic.append(
                    f"gateway_key=missing_http_{app_bundle.status_code}_len_{len(app_bundle.text)}_"
                    f"has_key_{str('GATEWAY_KEY' in app_bundle.text).lower()}"
                )
                return False, [], "无法初始化门户安全校验。", diagnostic
            diagnostic.append("gateway_key=ok")
            timestamp = str(int(time.time() * 1000))
            csrf = hashlib.md5(f"timestamp={timestamp},key={key_match.group(1)}".encode()).hexdigest()
            headers = {
                # The portal's post-CAS bootstrap request sends the literal
                # `Authorization: undefined`; the JWT is established in the
                # session cookie and is used by the subsequent API calls.
                "Authorization": "undefined",
                "Origin": PORTAL_BASE,
                "Referer": f"{PORTAL_BASE}/",
                "X-Requested-With": "XMLHttpRequest",
                "Content-Type": "application/x-www-form-urlencoded;charset=utf-8",
                "gatewayAppId": "ly-upp",
                "csrfTimestamp": timestamp,
                "csrfToken": csrf,
            }
            info_response = session.post(
                f"{PORTAL_BASE}/tryLoginUserInfo", data=b"", headers=headers,
                timeout=20, allow_redirects=False,
            )
            if info_response.status_code != 200:
                diagnostic.append(f"portal_user=http_{info_response.status_code}")
                return False, [], "融合门户登录未完成，请稍后重试。", diagnostic
            try:
                info_payload = info_response.json()
            except ValueError:
                diagnostic.append(
                    f"portal_user=invalid_json_http_{info_response.status_code}_len_{len(info_response.text)}"
                )
                return False, [], "融合门户返回了无法识别的登录结果。", diagnostic
            if not info_payload.get("meta", {}).get("success", False):
                meta = info_payload.get("meta")
                if isinstance(meta, dict):
                    message = meta.get("message")
                    code = meta.get("statusCode") or meta.get("code")
                    message_text = str(message or "")
                    if any(word in message_text for word in ("登录", "认证", "令牌", "token", "授权")):
                        message_class = "auth"
                    elif any(word in message_text for word in ("参数", "请求", "校验", "签名")):
                        message_class = "request"
                    else:
                        message_class = "other"
                    diagnostic.append(
                        f"portal_user=rejected_http_{info_response.status_code}_"
                        f"code_{str(code)[:32]}_message_{message_class}"
                    )
                else:
                    diagnostic.append(
                        f"portal_user=rejected_http_{info_response.status_code}_meta_{type(meta).__name__}"
                    )
                return False, [], "融合门户登录未完成，请稍后重试。", diagnostic
            portal_user = _portal_user(info_payload, session)
            diagnostic.append("portal_user=ok")

            timestamp = str(int(time.time() * 1000))
            csrf = hashlib.md5(f"timestamp={timestamp},key={key_match.group(1)}".encode()).hexdigest()
            notice_headers = {
                "Authorization": portal_user["token"],
                "Origin": PORTAL_BASE,
                "Referer": f"{PORTAL_BASE}/",
                "X-Requested-With": "XMLHttpRequest",
                "loginUserId": portal_user["user_id"],
                "loginUserName": portal_user["user_id"],
                "loginUserDeptId": portal_user["department_id"],
                "loginUserOrgId": portal_user["org_id"],
                "csrfTimestamp": timestamp,
                "csrfToken": csrf,
            }
            response = session.get(
                f"{PORTAL_BASE}/api/upp/contentDisplay/queryAppointCard/{NOTICE_CARD_ID}",
                params={"_t": int(time.time())}, headers=notice_headers, timeout=20,
                allow_redirects=False,
            )
            if response.status_code != 200:
                diagnostic.append(f"notices=http_{response.status_code}")
                return False, [], "无法获取教务公告，请稍后重试。", diagnostic
            try:
                notice_payload = response.json()
            except ValueError:
                diagnostic.append(f"notices=invalid_json_http_{response.status_code}_len_{len(response.text)}")
                return False, [], "教务公告返回格式异常，请稍后重试。", diagnostic
            try:
                items = _parse_notice_items(notice_payload)
            except RuntimeError:
                data = notice_payload.get("data") if isinstance(notice_payload, dict) else None
                content = data.get("contentDisplay") if isinstance(data, dict) else None
                raw = data.get("data") if isinstance(data, dict) else None
                row_count = "unknown"
                if isinstance(raw, list):
                    row_count = str(len(raw))
                elif isinstance(raw, str):
                    try:
                        parsed = json.loads(raw)
                        row_count = str(len(parsed)) if isinstance(parsed, list) else type(parsed).__name__
                    except (TypeError, ValueError):
                        row_count = "invalid"
                diagnostic.append(
                    f"notices=shape_error_root_{type(notice_payload).__name__}_"
                    f"data_{type(data).__name__}_content_{type(content).__name__}_rows_{row_count}_"
                    f"shape_{json.dumps(_safe_shape(notice_payload), ensure_ascii=False, separators=(',', ':'))[:500]}"
                )
                return False, [], "教务公告返回格式异常，请稍后重试。", diagnostic
            diagnostic.append(f"notices=ok_count_{len(items)}")
            return True, items, "", diagnostic
    except Exception as error:
        diagnostic.append("exception=" + type(error).__name__)
        return False, [], "教务公告获取失败，请检查校园账号或稍后重试。", diagnostic
    finally:
        diagnostic.append(f"elapsed_ms_{round((time.monotonic() - started) * 1000)}")


def fetch_portal_schedule(path: Path, account: str, password: str) -> tuple[bool, list[dict[str, str]], str, list[str], str | None, str | None]:
    """Fetch the timetable through the authenticated ZF academic system."""
    started = time.monotonic()
    diagnostic = ["start"]
    try:
        with ENGINE_LOCK:
            session, _, diagnostic = _portal_authenticated_session(
                path, account, password, service=ACADEMIC_CAS_SERVICE,
            )

            menu = _safe_campus_request(
                session,
                "GET",
                f"http://{ACADEMIC_HOST}/jwglxt/xtgl/index_initMenu.html",
                params={"jsdm": "xs", "_t": int(time.time() * 1000), "echarts": "1"},
                headers={
                    "Accept": "*/*",
                    "Referer": f"http://{ACADEMIC_HOST}/jwglxt/",
                    "X-Requested-With": "XMLHttpRequest",
                },
                timeout=20,
            )
            if menu.status_code != 200 or "/login" in (urlparse(menu.url).path or ""):
                diagnostic.append(f"jw_menu=http_{menu.status_code}")
                return False, [], "教务系统登录未完成，请稍后重试。", diagnostic, None, None

            now = datetime.now(ZoneInfo("Asia/Shanghai"))
            year = now.year if now.month >= 8 else now.year - 1
            term = "3" if now.month >= 8 else "12"
            response = _safe_campus_request(
                session,
                "POST",
                f"http://{ACADEMIC_HOST}/jwglxt/kbcx/xskbcx_cxXsgrkb.html",
                params={"gnmkdm": "N2151"},
                data={
                    "xnm": str(year),
                    "xqm": term,
                    "kzlx": "ck",
                    "xsdm": "",
                    "kclbdm": "",
                    "kclxdm": "",
                },
                headers={
                    "Accept": "*/*",
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                    "Origin": f"http://{ACADEMIC_HOST}",
                    "Referer": f"http://{ACADEMIC_HOST}/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default",
                    "X-Requested-With": "XMLHttpRequest",
                },
                timeout=20,
            )
            if response.status_code != 200:
                diagnostic.append(f"schedule=http_{response.status_code}")
                return False, [], "无法获取我的课表，请稍后重试。", diagnostic, None, None
            try:
                payload = response.json()
                items = _parse_schedule_items(payload)
            except ValueError:
                diagnostic.append(f"schedule=invalid_json_http_{response.status_code}_len_{len(response.text)}")
                return False, [], "课表返回格式异常，请稍后重试。", diagnostic, None, None
            except RuntimeError:
                diagnostic.append(
                    f"schedule=invalid_shape_http_{response.status_code}_"
                    f"shape_{json.dumps(_safe_shape(payload), ensure_ascii=False, separators=(',', ':'))[:500]}"
                )
                return False, [], "课表返回格式异常，请稍后重试。", diagnostic, None, None

            semester_start = None
            semester_end = None
            try:
                calendar = _safe_campus_request(
                    session,
                    "POST",
                    f"http://{ACADEMIC_HOST}/jwglxt/xtgl/index_cxAreaFive.html",
                    params={"localeKey": "zh_CN", "gnmkdm": "index"},
                    data=b"",
                    headers={
                        "Accept": "text/html, */*; q=0.01",
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                        "Origin": f"http://{ACADEMIC_HOST}",
                        "Referer": f"http://{ACADEMIC_HOST}/jwglxt/xtgl/index_initMenu.html?jsdm=xs&echarts=1",
                        "X-Requested-With": "XMLHttpRequest",
                    },
                    timeout=20,
                )
                if calendar.status_code == 200:
                    semester_start, semester_end = _parse_academic_calendar_range(calendar.text)
                    diagnostic.append("calendar=ok")
                else:
                    diagnostic.append(f"calendar=http_{calendar.status_code}")
            except (RuntimeError, ValueError):
                diagnostic.append("calendar=date_range_unavailable")
            except Exception as error:
                diagnostic.append(f"calendar=exception_{type(error).__name__}")
            diagnostic.append(f"schedule=ok_count_{len(items)}")
            return True, items, "", diagnostic, semester_start, semester_end
    except RuntimeError as error:
        diagnostic.append(f"schedule=auth_failed_reason_{type(error).__name__}")
        return False, [], str(error), diagnostic, None, None
    except Exception as error:
        diagnostic.append(f"schedule=exception_{type(error).__name__}")
        return False, [], "我的课表获取失败，请检查校园账号或稍后重试。", diagnostic, None, None
    finally:
        # Keep diagnostics useful without including credentials or response bodies.
        pass


def run_check_detailed(path: Path, account: str, password: str) -> tuple[str, str, int, str]:
    started = time.monotonic()
    CAMPUS_FAILURE.code = ""
    output = io.StringIO()
    status = "failure"
    error_code = "unknown"
    submitted = False
    safe_message = "查寝未完成，请检查校园账号或稍后重试。"
    try:
        with ENGINE_LOCK:
            namespace = _load_namespace(path)
            namespace["USER_ID"] = account
            namespace["USER_PWD"] = password
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                session = namespace["init"]()
                ticket, login_error = namespace["login"](session)
                if not ticket:
                    error = str(login_error or "").upper()
                    error_code = "invalid_credentials" if any(value in error for value in ("NOUSER", "PASSERROR", "密码错误", "账号不存在")) else "login_failed"
                    if any(value in error for value in ("LOCK", "锁定", "频繁")):
                        raise CampusRequestBlocked("account_locked")
                    raise RuntimeError("campus_login_failed")
                namespace["UpdateCookie"](session, ticket)
                namespace["select_role"](session)
                submitted = True
                result = namespace["doWork"](session)
            if namespace["is_success_result"](result):
                status = "success"
                error_code = ""
                safe_message = "查寝完成。"
            else:
                failure_type = namespace.get("classify_failure", lambda _: "unknown")(result)
                error_code = failure_type
                if any(value in str(result) for value in ("访问频繁", "请求过于频繁", "账号锁定")):
                    raise CampusRequestBlocked("rate_limited")
                descriptions = {
                    "captcha": "校园平台验证码识别未通过，本次查寝未完成。",
                    "already_checked": "校园平台显示今天已经签到过，本次没有重复提交。",
                    "response_parse_error": "校园平台返回格式异常，未能确认查寝结果。请查看校园平台状态。",
                    "invalid_syntax": "校园平台验证码处理失败，本次查寝未完成。",
                    "file_not_found": "查寝任务运行所需文件缺失，请联系管理员。",
                    "time_slot_invalid": "当前不在校园平台允许的查寝时段内。",
                }
                safe_message = descriptions.get(
                    failure_type,
                    "校园平台未确认查寝成功，请查看校园平台状态。",
                )
    except CampusRequestBlocked as error:
        error_code = error.code
        if submitted and error_code == "server_error":
            error_code = "unknown_outcome"
        safe_message = "校园平台限制了访问，本次任务已停止并进入冷却。" if error.code in ("rate_limited", "access_denied", "account_locked") else "校园平台服务异常，未能确认本次结果。"
    except Exception as error:
        import requests
        if isinstance(error, (requests.Timeout, requests.ConnectionError)):
            error_code = "unknown_outcome" if submitted else "network"
        safe_message = "查寝失败，请检查账号状态或稍后重试。"
    finally:
        output.close()
    return status, safe_message, round((time.monotonic() - started) * 1000), error_code


def run_check(path: Path, account: str, password: str) -> tuple[str, str, int]:
    return run_check_detailed(path, account, password)[:3]


def verify_credentials(path: Path, account: str, password: str) -> tuple[bool, str]:
    output = io.StringIO()
    try:
        with ENGINE_LOCK:
            namespace = _load_namespace(path)
            namespace["USER_ID"] = account
            namespace["USER_PWD"] = password
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                session = namespace["init"]()
                ticket, error = namespace["login"](session)
            if ticket:
                return True, "校园平台登录验证成功。"
            safe_error = str(error or "").upper()
            if "NOUSER" in safe_error or "账号不存在" in safe_error:
                return False, "校园平台账号不存在。"
            if "PASSERROR" in safe_error or "密码错误" in safe_error:
                return False, "校园平台密码错误。"
            if "CODEFALSE" in safe_error or "验证码错误" in safe_error:
                return False, "验证码识别失败，请重试验证。"
            return False, "校园平台登录验证失败，请稍后重试。"
    except Exception:
        return False, "无法连接校园平台，请稍后重试。"
    finally:
        output.close()


def serialize_credentials(account: str, password: str) -> bytes:
    return json.dumps({"account": account, "password": password}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def deserialize_credentials(value: bytes) -> tuple[str, str]:
    payload = json.loads(value.decode("utf-8"))
    return str(payload["account"]), str(payload["password"])
