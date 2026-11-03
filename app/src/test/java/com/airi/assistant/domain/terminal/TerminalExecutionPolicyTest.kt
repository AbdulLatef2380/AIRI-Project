package com.airi.assistant.domain.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExecutionPolicyTest {
    @Test
    fun readOnlyCommandsAreAllowedByTheBoundedPolicy() {
        val decision = TerminalExecutionPolicy.evaluate("ls")
        assertTrue(decision.allowed)
    }

    @Test
    fun blankAndOversizedCommandsAreRejectedWithoutExecution() {
        assertFalse(TerminalExecutionPolicy.evaluate(" ").allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("x".repeat(8_193)).allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("cat ../secret.txt").allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("rm -rf ./output").allowed)
        assertTrue(TerminalExecutionPolicy.evaluate("rm -rf ./output").requiresApproval)
        assertFalse(TerminalExecutionPolicy.evaluate("echo safe; cat /etc/passwd").allowed)
    }

    // ── Report 3, T1: per-tool option allowlist ────────────────────────────────

    private fun allowed(cmd: String) = TerminalExecutionPolicy.evaluate(cmd).allowed

    @Test
    fun commonReadOnlyInvocationsStillWork() {
        listOf(
            "ls -la", "ls -l ./app", "cat -n ./notes.txt", "head -n 20 ./log.txt", "head -5 ./log.txt",
            "tail -n 50 ./log.txt", "wc -l ./a.txt", "sort -r ./a.txt", "uniq -c ./a.txt",
            "grep -rn TODO ./src", "grep -i error ./log.txt", "find . -name foo.kt -type f",
            "find . -maxdepth 2 -type d", "sed -n 5,10p ./a.txt", "echo hello", "echo -n hello",
            "git status", "git log --oneline -n 5", "git diff --stat", "git branch --list",
            "git branch -a", "git show --stat HEAD",
        ).forEach { assertTrue("expected allowed: $it", allowed(it)) }
    }

    @Test
    fun findOptionsThatWriteOrExecuteAreRefused() {
        listOf(
            "find . -fprint out.txt", "find . -fls out.txt", "find . -fprintf out.txt x",
            "find . -exec cat x", "find . -execdir cat x", "find . -delete", "find . -ok cat x",
        ).forEach { assertFalse("expected refused: $it", allowed(it)) }
    }

    @Test
    fun sedCannotExecuteWriteOrEditInPlace() {
        listOf(
            "sed e ./script.txt",          // GNU sed: execute each line as a command
            "sed s,a,b,e ./a.txt",         // s///e flag
            "sed 1w out.txt ./a.txt",      // w command writes a file
            "sed -i 1d ./a.txt",           // in place
            "sed -f ./prog.sed ./a.txt",   // script file
            "sed -e 1p ./a.txt",
            "sed --in-place 1d ./a.txt",
            "sed",                         // no script
        ).forEach { assertFalse("expected refused: $it", allowed(it)) }
    }

    @Test
    fun awkProgramFilesAndSystemCallsAreRefused() {
        listOf(
            "awk -f ./prog.awk ./a.txt", "awk --file ./prog.awk ./a.txt",
            "awk BEGIN{system} ./a.txt", "awk ENVIRON ./a.txt", "awk -i inplace 1 ./a.txt",
        ).forEach { assertFalse("expected refused: $it", allowed(it)) }
        assertTrue(allowed("awk -F , 1 ./a.txt"))
    }

    @Test
    fun sortOutputOptionIsRefused() {
        assertFalse(allowed("sort -o ./out.txt ./a.txt"))
        assertFalse(allowed("sort -ro ./out.txt ./a.txt"))
        assertFalse(allowed("sort --output=./out.txt ./a.txt"))
    }

    @Test
    fun gitCannotMutateOrRunExternalPrograms() {
        listOf(
            "git branch -D feature", "git branch -m old new", "git branch newbranch", "git branch -d feature",
            "git diff --ext-diff", "git show --textconv HEAD", "git log --output=./x.txt",
            "git -c core.pager=sh log", "git config user.name x", "git checkout main",
            "git diff -O./order", "git log --open-files-in-pager=sh",
        ).forEach { assertFalse("expected refused: $it", allowed(it)) }
    }

    @Test
    fun tailFollowAndUnknownOptionsAreRefused() {
        assertFalse(allowed("tail -f ./log.txt"))
        assertFalse(allowed("tail -F ./log.txt"))
        assertFalse(allowed("ls --unknown"))
        assertFalse(allowed("cat -v ./a.txt"))
        assertFalse(allowed("echo -e hello"))
    }
}
