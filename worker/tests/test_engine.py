from pathlib import Path

from dormcheck.engine import (
    PORTAL_SERVICE,
    _evaluate_captcha_expression,
    _load_namespace,
    _normalize_ticket,
    _is_safe_cas_ticket_candidate,
    _parse_notice_items,
    _parse_academic_calendar_range,
    _parse_schedule_items,
    _portal_login_failure_message,
    _cookie_value,
    _keep_most_specific_cookie,
    _parse_notice_items,
    run_check,
    run_check_detailed,
    CampusRequestBlocked,
    _stop_rejected_campus_response,
    verify_credentials,
)


def fake_engine(tmp_path: Path) -> Path:
    path = tmp_path / "engine.py"
    path.write_text(
        "USER_ID = 'old-account-secret'\n"
        "USER_PWD = 'old-password-secret'\n"
        "SENDER_PASS = 'old-mail-secret'\n"
        "def init(): return {}\n"
        "def login(session): return ('ticket', None) if USER_ID == 'new-account' and USER_PWD == 'new-password' else (None, 'private error')\n"
        "def UpdateCookie(session, ticket): return None\n"
        "def select_role(session): return None\n"
        "def doWork(session): print(USER_PWD); return 'OK'\n"
        "def is_success_result(value): return value == 'OK'\n",
        encoding="utf-8",
    )
    return path


def test_engine_source_strips_embedded_credential_values(tmp_path):
    namespace = _load_namespace(fake_engine(tmp_path))
    assert "USER_ID" not in namespace
    assert "USER_PWD" not in namespace
    assert "SENDER_PASS" not in namespace


def test_engine_uses_submitted_credentials_and_returns_only_safe_result(tmp_path):
    status, message, duration = run_check(fake_engine(tmp_path), "new-account", "new-password")
    assert status == "success"
    assert message == "查寝完成。"
    assert duration >= 0


def test_engine_hides_script_login_errors(tmp_path):
    status, message, _ = run_check(fake_engine(tmp_path), "other-account", "bad-password")
    assert status == "failure"
    assert "private error" not in message
    assert "bad-password" not in message


def test_engine_maps_known_campus_results_to_safe_messages(tmp_path):
    cases = {
        "您已签到,请勿重复签到": "校园平台显示今天已经签到过，本次没有重复提交。",
        "当前时段不在考勤时段内": "当前不在校园平台允许的查寝时段内。",
        "验证码错误": "校园平台验证码识别未通过，本次查寝未完成。",
    }

    for campus_result, expected in cases.items():
        path = tmp_path / "engine.py"
        path.write_text(
            "def init(): return {}\n"
            "def login(session): return ('ticket', None)\n"
            "def UpdateCookie(session, ticket): return None\n"
            "def select_role(session): return None\n"
            f"def doWork(session): return {campus_result!r}\n"
            "def is_success_result(value): return False\n"
            "def classify_failure(value):\n"
            "    if '重复签到' in value: return 'already_checked'\n"
            "    if '考勤时段' in value: return 'time_slot_invalid'\n"
            "    if '验证码' in value: return 'captcha'\n"
            "    return 'unknown'\n",
            encoding="utf-8",
        )

        status, message, _ = run_check(path, "account", "password")
        assert status == "failure"
        assert message == expected


def test_engine_verifies_without_submitting_a_check(tmp_path):
    valid, message = verify_credentials(fake_engine(tmp_path), "new-account", "new-password")
    assert valid is True
    assert message == "校园平台登录验证成功。"


def test_check_only_retries_transport_failures_before_submission(tmp_path):
    path = tmp_path / "transport.py"
    path.write_text(
        "import requests\n"
        "def init(): return {}\n"
        "def login(session): raise requests.ConnectionError('private detail')\n",
        encoding="utf-8",
    )
    result = run_check_detailed(path, "account", "password")
    assert result[0] == "failure"
    assert result[3] == "network"
    assert "private detail" not in result[1]
    path.write_text(
        "import requests\n"
        "def init(): return {}\n"
        "def login(session): return ('ticket', None)\n"
        "def UpdateCookie(session, ticket): return None\n"
        "def select_role(session): return None\n"
        "def doWork(session): raise requests.Timeout('private detail')\n",
        encoding="utf-8",
    )
    assert run_check_detailed(path, "account", "password")[3] == "unknown_outcome"


def test_campus_response_hook_stops_risk_and_server_responses():
    from types import SimpleNamespace
    import pytest

    for status, expected in ((403, "access_denied"), (429, "rate_limited"), (503, "server_error")):
        with pytest.raises(CampusRequestBlocked) as raised:
            _stop_rejected_campus_response(SimpleNamespace(status_code=status))
        assert raised.value.code == expected


def test_loaded_engine_installs_response_guard(tmp_path):
    path = tmp_path / "guarded.py"
    path.write_text("import requests\ndef init(): return requests.Session()\n", encoding="utf-8")
    session = _load_namespace(path)["init"]()
    assert _stop_rejected_campus_response in session.hooks["response"]


def test_captcha_expression_parser_only_evaluates_bounded_arithmetic():
    assert _evaluate_captcha_expression("(12 + 8) / 4") == 5
    assert _evaluate_captcha_expression("7 / 2") == 3.5
    assert _evaluate_captcha_expression("9 * 3 - 2") == 25
    for expression in ("__import__('os')", "2 ** 100", "1 / 0", "1 % 2"):
        try:
            _evaluate_captcha_expression(expression)
            assert False, f"accepted unsafe expression: {expression}"
        except ValueError:
            pass


def test_portal_notice_parser_only_returns_safe_fields_and_links():
    payload = {
        "data": {
            "contentDisplay": {
                "data": '[{"BT":"通知","CJSJ":"2026-10-10","URL":"https://jw.gzist.edu.cn/news/1"},'
                '{"BT":"坏链接","CJSJ":"2026-10-10","URL":"http://jw.gzist.edu.cn/news/2"},'
                '{"BT":"外站","CJSJ":"2026-10-10","URL":"https://example.com/"}]'
            }
        }
    }
    assert _parse_notice_items(payload) == [{
        "title": "通知",
        "date": "2026-10-10",
        "url": "https://jw.gzist.edu.cn/news/1",
    }]


def test_portal_login_overrides_only_login_service_and_replaces_eval(tmp_path):
    path = tmp_path / "engine.py"
    path.write_text(
        "USER_ID = 'old-secret'\n"
        "USER_PWD = 'old-secret'\n"
        "def getCode(expr): return eval(expr, {'__builtins__': {}}, {})\n"
        "def login(session): return 'https://xsfw.gzist.edu.cn/app', None\n"
        "def UpdateCookie(session, ticket): return 'https://xsfw.gzist.edu.cn/app'\n",
        encoding="utf-8",
    )
    namespace = _load_namespace(path, portal_service="https://portal.gzist.edu.cn/")
    assert "USER_ID" not in namespace
    assert namespace["login"](None)[0] == "https://portal.gzist.edu.cn/"
    assert namespace["UpdateCookie"](None, "ticket") == "https://xsfw.gzist.edu.cn/app"
    assert namespace["getCode"]("7 + 5") == 12


def test_portal_cas_service_matches_registered_portal_url():
    assert PORTAL_SERVICE == "https://portal.gzist.edu.cn"


def test_normalize_ticket_accepts_only_extractable_ticket_values():
    assert _normalize_ticket('  "TGT-12345678"  ') == "TGT-12345678"
    assert _normalize_ticket({"data": {"ticket": "TGT-abcdefgh"}}) == "TGT-abcdefgh"
    assert _normalize_ticket('{"data":{"ticket":"TGT-87654321"}}') == "TGT-87654321"
    assert _normalize_ticket(None) == ""


def test_safe_cas_ticket_candidate_accepts_bounded_url_safe_values():
    assert _is_safe_cas_ticket_candidate("TGT-12345678")
    assert _is_safe_cas_ticket_candidate("abc_def-123.456~xyz")
    assert not _is_safe_cas_ticket_candidate("short")
    assert not _is_safe_cas_ticket_candidate("ticket/with/path")
    assert not _is_safe_cas_ticket_candidate("ticket?query=value")
    assert not _is_safe_cas_ticket_candidate("x" * 201)


def test_parse_notice_items_accepts_nested_json_data():
    payload = {
        "meta": {"success": True},
        "data": {"contentDisplay": {"data": '[{"BT":"Notice","CJSJ":"2026-09-10","URL":"https://jw.gzist.edu.cn/jwglxt/x.html"}]'}},
    }
    assert _parse_notice_items(payload)[0]["title"] == "Notice"


def test_parse_schedule_items_normalizes_course_rows():
    payload = {"sjkList": [{"kcmc": "实践摘要"}], "kbList": [{"kcmc":"高等数学","xm":"张老师","cdmc":"5-310","xqj":"1","xqjmc":"星期一","jcs":"1-2","zcd":"1-16","kclbmc":"专业课"}]}
    assert _parse_schedule_items(payload) == [{
        "course": "高等数学", "teacher": "张老师", "room": "5-310",
        "weekday": "星期一", "periods": "1-2", "weeks": "1-16", "type": "专业课",
    }]


def test_parse_academic_calendar_range_reads_official_semester_dates():
    html = "<th>2026-2027学年1学期(2026-08-31至2027-01-29)</th>"
    assert _parse_academic_calendar_range(html) == ("2026-08-31", "2027-01-29")


def test_parse_academic_calendar_range_rejects_missing_or_invalid_dates():
    import pytest

    with pytest.raises(RuntimeError):
        _parse_academic_calendar_range("<th>学期信息暂不可用</th>")
    with pytest.raises(ValueError):
        _parse_academic_calendar_range("<th>2026-2027学年(2026-02-30至2027-01-29)</th>")


def test_parsers_accept_explicit_empty_payloads_only():
    assert _parse_notice_items({"data": {"contentDisplay": {"data": []}}}) == []
    assert _parse_schedule_items({"kbList": []}) == []


def test_schedule_parser_accepts_chinese_weekday_names():
    items = _parse_schedule_items({"kbList": [{"kcmc": "数据结构", "xqjmc": "星期三", "jcs": "3-4"}]})
    assert items[0]["weekday"] == "星期三"


def test_schedule_parser_rejects_partial_course_rows():
    import pytest

    with pytest.raises(RuntimeError):
        _parse_schedule_items({"kbList": [{"kcmc": "数据结构", "xqjmc": "星期二"}]})


def test_portal_login_failure_messages_only_expose_safe_categories():
    assert "验证码" in _portal_login_failure_message("CODEFALSE private detail")
    assert "账号密码" in _portal_login_failure_message("PASSERROR private detail")
    assert "认证中心" in _portal_login_failure_message("private detail")
    assert "private detail" not in _portal_login_failure_message("private detail")


def test_cookie_value_handles_duplicate_cookie_names():
    import requests

    session = requests.Session()
    session.cookies.set("JSESSIONID", "portal", domain="portal.gzist.edu.cn", path="/")
    session.cookies.set("JSESSIONID", "academic", domain="jw.gzist.edu.cn", path="/jwglxt")

    assert _cookie_value(session, "JSESSIONID", host="jw.gzist.edu.cn", path="/jwglxt/kbcx") == "academic"


def test_keep_most_specific_cookie_removes_duplicate_academic_scopes():
    import requests

    session = requests.Session()
    session.cookies.set("JSESSIONID", "broad", domain="jw.gzist.edu.cn", path="/")
    session.cookies.set("JSESSIONID", "academic", domain="jw.gzist.edu.cn", path="/jwglxt")
    session.cookies.set("JSESSIONID", "portal", domain="portal.gzist.edu.cn", path="/")

    assert _keep_most_specific_cookie(session, "JSESSIONID", "http://jw.gzist.edu.cn/jwglxt/") == "academic"
    matching = [cookie for cookie in session.cookies if cookie.name == "JSESSIONID" and cookie.domain == "jw.gzist.edu.cn"]
    assert [(cookie.path, cookie.value) for cookie in matching] == [("/jwglxt", "academic")]
