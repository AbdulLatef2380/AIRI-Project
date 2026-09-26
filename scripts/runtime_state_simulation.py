#!/usr/bin/env python3
"""Miniature runtime simulation for SecureStorage and ChatViewModel contracts.

This is not an Android/Keystore test. It models the documented boundaries so the
failure semantics and out-of-order session guards can be exercised without SDK.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from itertools import permutations
from typing import Dict, List, Optional
import sys


class SessionState(Enum):
    NOT_STARTED = "NotStarted"
    LOADING = "Loading"
    READY = "Ready"
    EMPTY = "Empty"
    FAILED = "Failed"


class Fault(RuntimeError):
    pass


class MiniDisk:
    def __init__(self) -> None:
        self.values: Dict[str, str] = {}
        self.write_count = 0

    def write(self, key: str, value: str) -> None:
        self.write_count += 1
        self.values[key] = value

    def read(self, key: str) -> Optional[str]:
        return self.values.get(key)


class MiniSecureStorage:
    """Models SecureStorage: encrypted disk or process-local memory fallback."""

    def __init__(self, disk: MiniDisk, keystore_ok: bool, disk_read_ok: bool = True) -> None:
        self.disk = disk
        self.is_encrypted = keystore_ok
        self.disk_read_ok = disk_read_ok
        self.memory: Dict[str, str] = {}
        self._source = "encrypted-disk" if keystore_ok else "memory-only"

    def save(self, key: str, value: str) -> bool:
        if not key.strip():
            return False
        if not self.is_encrypted:
            self.memory[key] = value
            return True
        self.disk.write(key, value)
        return True

    def get(self, key: str) -> Optional[str]:
        if not self.is_encrypted:
            return self.memory.get(key)
        if not self.disk_read_ok:
            raise Fault("keystore read failed")
        return self.disk.read(key)

    def clear(self, key: str) -> bool:
        if not self.is_encrypted:
            self.memory.pop(key, None)
            return True
        if not self.disk_read_ok:
            raise Fault("keystore clear failed")
        self.disk.values.pop(key, None)
        return True

    def fresh_process(self) -> "MiniSecureStorage":
        # A fresh fallback instance cannot see process-local values.
        return MiniSecureStorage(self.disk, self.is_encrypted, self.disk_read_ok)


@dataclass(frozen=True)
class Completion:
    token: int
    session_id: str
    outcome: str  # success, empty, failure
    messages: List[str]
    error: Optional[str] = None


class MiniSessionLoader:
    """Models LatestOperationGate + explicit SessionLoadState projection."""

    def __init__(self, database: Dict[str, List[str]]) -> None:
        self.database = database
        self.next_token = 0
        self.current_token = 0
        self.current_session = ""
        self.messages: List[str] = []
        self.state = SessionState.NOT_STARTED
        self.error: Optional[str] = None

    def begin(self, session_id: str) -> int:
        if not session_id:
            self.state = SessionState.FAILED
            self.error = "Session id is blank"
            return 0
        self.next_token += 1
        self.current_token = self.next_token
        self.state = SessionState.LOADING
        self.error = None
        return self.current_token

    def complete(self, completion: Completion) -> bool:
        if completion.token != self.current_token:
            return False
        if completion.outcome == "failure":
            self.state = SessionState.FAILED
            self.error = completion.error or "Session could not be loaded"
            return True
        self.current_session = completion.session_id
        self.messages = list(completion.messages)
        self.error = None
        self.state = SessionState.EMPTY if not self.messages else SessionState.READY
        return True

    def db_completion(self, token: int, session_id: str, fail: bool = False) -> Completion:
        if fail:
            return Completion(token, session_id, "failure", [], "db read failed")
        return Completion(token, session_id, "success", self.database.get(session_id, []))


def check(name: str, condition: bool, detail: str) -> None:
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {name}: {detail}")
    if not condition:
        raise AssertionError(name)


def test_keystore() -> None:
    disk = MiniDisk()
    secure = MiniSecureStorage(disk, keystore_ok=True)
    check("encrypted write", secure.is_encrypted and secure.save("github", "<REDACTED>"), "healthy Keystore writes through encrypted store")
    check("encrypted persistence", disk.read("github") == "<REDACTED>", "credential is persisted only in the encrypted-disk model")
    fresh = secure.fresh_process()
    check("encrypted restart read", fresh.get("github") == "<REDACTED>", "encrypted value survives a new process")

    fallback = MiniSecureStorage(disk, keystore_ok=False)
    before = disk.write_count
    check("fallback advertises unavailable encryption", not fallback.is_encrypted, "isEncrypted=false")
    check("fallback write is memory-only", fallback.save("telegram", "<REDACTED>") and disk.write_count == before, "no plaintext disk write is attempted")
    check("fallback current-process read", fallback.get("telegram") == "<REDACTED>", "current process can use transient value")
    check("fallback restart loses value", fallback.fresh_process().get("telegram") is None, "value is not persisted after process restart")
    check("fallback clear", fallback.clear("telegram") and fallback.get("telegram") is None, "clear is safe in memory-only mode")

    broken_read = MiniSecureStorage(disk, keystore_ok=True, disk_read_ok=False)
    try:
        broken_read.get("github")
    except Fault:
        read_failed = True
    else:
        read_failed = False
    check("Keystore read fault is visible", read_failed, "fault is not converted to a credential-present success")


def test_sessions() -> None:
    loader = MiniSessionLoader({"A": ["a1"], "B": [], "C": ["c1", "c2"]})
    a = loader.begin("A")
    b = loader.begin("B")
    check("latest token wins", not loader.complete(loader.db_completion(a, "A")), "late A completion cannot overwrite newer B load")
    check("empty is explicit", loader.complete(loader.db_completion(b, "B")) and loader.state is SessionState.EMPTY and loader.messages == [], "valid empty session is distinct from failure")

    c = loader.begin("C")
    check("DB failure is explicit", loader.complete(loader.db_completion(c, "C", fail=True)) and loader.state is SessionState.FAILED and loader.messages == [], "DB fault yields Failed, not Empty or Ready")
    check("failed session identity retained", loader.error == "db read failed", "failure reason remains available for retry/UI")

    # Exhaustively exercise completion order for three loads. The newest token
    # must be the only completion that may commit, regardless of arrival order.
    for order in permutations(("A", "B", "C")):
        sim = MiniSessionLoader({"A": ["a"], "B": [], "C": ["c"]})
        tokens = {sid: sim.begin(sid) for sid in ("A", "B", "C")}
        accepted = [sid for sid in order if sim.complete(sim.db_completion(tokens[sid], sid))]
        check(f"completion order {''.join(order)}", accepted == ["C"] and sim.current_session == "C", "only newest session completion commits")


def main() -> int:
    print("Runtime state simulation (Android SDK-independent; secrets redacted)")
    try:
        test_keystore()
        test_sessions()
    except AssertionError:
        print("summary: FAIL")
        return 1
    print("summary: 19/19 checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
