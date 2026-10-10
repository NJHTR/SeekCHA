from prepare_engine import sanitized_source


def test_sanitizer_removes_embedded_credentials_only():
    cleaned = sanitized_source(
        "USER_ID = 'private-account'\nUSER_PWD = 'private-password'\ndef login(): return True\n",
        "engine.py",
    )
    assert "private-account" not in cleaned
    assert "private-password" not in cleaned
    assert "def login(): return True" in cleaned


def test_sanitizer_requires_both_credential_assignments():
    try:
        sanitized_source("def login(): return True\n", "engine.py")
    except RuntimeError as error:
        assert "expected credential assignments" in str(error)
    else:
        raise AssertionError("missing embedded credentials should be rejected")
