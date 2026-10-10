import ast
import os
import shutil
import sys
import tempfile
from pathlib import Path


SECRET_ASSIGNMENTS = {"USER_ID", "USER_PWD", "SENDER_EMAIL", "SENDER_PASS", "RECEIVER"}
REQUIRED_ASSIGNMENTS = {"USER_ID", "USER_PWD"}


def sanitized_source(source: str, filename: str) -> str:
    tree = ast.parse(source, filename=filename)
    lines = source.splitlines(keepends=True)
    removed: set[str] = set()
    spans = []
    for node in tree.body:
        if not isinstance(node, (ast.Assign, ast.AnnAssign)):
            continue
        targets = node.targets if isinstance(node, ast.Assign) else [node.target]
        names = {target.id for target in targets if isinstance(target, ast.Name)}
        matched = names & SECRET_ASSIGNMENTS
        if matched:
            removed.update(matched)
            spans.append((node.lineno - 1, node.end_lineno))
    if not REQUIRED_ASSIGNMENTS.issubset(removed):
        raise RuntimeError("expected credential assignments were not found; source was not changed")
    for start, end in spans:
        lines[start:end] = ["\n" for _ in range(end - start)]
    result = "".join(lines)
    compile(result, filename, "exec")
    check = ast.parse(result, filename=filename)
    for node in check.body:
        if not isinstance(node, (ast.Assign, ast.AnnAssign)):
            continue
        targets = node.targets if isinstance(node, ast.Assign) else [node.target]
        names = {target.id for target in targets if isinstance(target, ast.Name)}
        if names & SECRET_ASSIGNMENTS:
            raise RuntimeError("credential assignment remained after sanitizing")
    return result


def prepare(source_path: Path, target_dir: Path) -> None:
    cleaned = sanitized_source(source_path.read_text(encoding="utf-8"), str(source_path))
    target_dir.mkdir(mode=0o750, parents=True, exist_ok=True)
    target = target_dir / source_path.name
    fd, temporary_name = tempfile.mkstemp(prefix=".engine-", dir=target_dir)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as output:
            output.write(cleaned)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary_name, 0o640)
        os.replace(temporary_name, target)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    shutil.copyfile(source_path.with_name("g5116.js"), target_dir / "g5116.js")
    os.chmod(target_dir / "g5116.js", 0o640)


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: prepare_engine.py SOURCE_GO_TO_BED_PY TARGET_ENGINE_DIR", file=sys.stderr)
        return 2
    prepare(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
    print("Sanitized engine prepared; no account credentials were copied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
