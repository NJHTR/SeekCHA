import json
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from dormcheck.engine import CAMPUS_FAILURE, fetch_portal_notices, fetch_portal_schedule, run_check_detailed, verify_credentials


def main() -> None:
    action, engine_path = sys.argv[1:3]
    payload = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    account = str(payload["account"])
    password = str(payload["password"])
    if action == "verify":
        verified, message = verify_credentials(Path(engine_path), account, password)
        result = {"verified": verified, "message": message}
    elif action == "check":
        status, message, duration_ms, error_code = run_check_detailed(Path(engine_path), account, password)
        result = {"status": status, "message": message, "duration_ms": duration_ms, "error_code": error_code}
    elif action == "notices":
        ok, items, message, diagnostic = fetch_portal_notices(Path(engine_path), account, password)
        result = {"ok": ok, "items": items, "message": message, "diagnostic": diagnostic,
                  "data_complete": ok}
    elif action == "academic-schedule":
        ok, items, message, diagnostic, semester_start, semester_end = fetch_portal_schedule(Path(engine_path), account, password)
        now = datetime.now(ZoneInfo("Asia/Shanghai"))
        academic_year = now.year if now.month >= 8 else now.year - 1
        academic_term = "3" if now.month >= 8 else "12"
        result = {"ok": ok, "items": items, "message": message, "diagnostic": diagnostic,
                  "data_complete": ok, "academic_year": str(academic_year),
                  "academic_term": academic_term,
                  "academic_scope": f"{academic_year}-{academic_term}",
                  "semester_start": semester_start, "semester_end": semester_end}
    else:
        raise ValueError("unsupported action")
    campus_error = getattr(CAMPUS_FAILURE, "code", "")
    if campus_error and ("error_code" not in result or campus_error in ("rate_limited", "access_denied")):
        result["error_code"] = campus_error
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
