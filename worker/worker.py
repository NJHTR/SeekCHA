import json
import sys
from pathlib import Path

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
        result = {"ok": ok, "items": items, "message": message, "diagnostic": diagnostic}
    elif action == "academic-schedule":
        ok, items, message, diagnostic = fetch_portal_schedule(Path(engine_path), account, password)
        result = {"ok": ok, "items": items, "message": message, "diagnostic": diagnostic}
    else:
        raise ValueError("unsupported action")
    campus_error = getattr(CAMPUS_FAILURE, "code", "")
    if campus_error and ("error_code" not in result or campus_error in ("rate_limited", "access_denied")):
        result["error_code"] = campus_error
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
