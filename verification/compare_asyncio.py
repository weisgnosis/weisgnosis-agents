"""Matched asyncio contracts and labelled negative controls; no framework benchmark."""

import asyncio
from dataclasses import dataclass
import json
import platform
import sys


@dataclass(frozen=True)
class Entry:
    kind: str
    identity: str
    value: str
    reason: str = ""


class ReferenceAgent:
    """Minimal application-owned history/reconciliation, not a Python framework."""

    def __init__(self) -> None:
        self.history: tuple[Entry, ...] = ()
        self.entered = (asyncio.Event(), asyncio.Event())
        self.persisted = asyncio.Event()
        self.cleanup_entered = asyncio.Event()
        self.release_cleanup = asyncio.Event()
        self.cleaned = asyncio.Event()
        self.active = 0
        self.peak = 0

    async def tool(self, index: int) -> None:
        assert tuple(entry.identity for entry in self.history if entry.kind == "call") == (
            "completed-call", "held-call"
        )
        self.active += 1
        self.peak = max(self.peak, self.active)
        self.entered[index].set()
        try:
            for entered in self.entered:
                await entered.wait()
            if index == 0:
                self.history += (Entry("success", "completed-call", "completed-evidence"),)
                self.persisted.set()
            else:
                await asyncio.Event().wait()
        finally:
            if index == 1:
                self.cleanup_entered.set()
                await self.release_cleanup.wait()
                self.cleaned.set()
            self.active -= 1

    async def run(self) -> None:
        self.history += (Entry("call", "completed-call", "{}"), Entry("call", "held-call", "{}"))
        try:
            async with asyncio.TaskGroup() as tasks:
                for index in range(2):
                    tasks.create_task(self.tool(index))
        except asyncio.CancelledError:
            completed = tuple(entry.identity for entry in self.history if entry.kind == "success")
            self.history += tuple(
                Entry("error", entry.identity, "interrupted")
                for entry in self.history if entry.kind == "call" and entry.identity not in completed
            )
            self.history += (Entry("recovery", "assistant", "I noticed that you have interrupted me. What can I do for you?", "interrupted"),)
            raise

    async def continue_turn(self) -> None:
        assert self.active == 0 and self.cleaned.is_set()
        self.history += (Entry("reply", "assistant", "continued-after-cancellation"),)


async def matched_contracts() -> dict[str, bool]:
    agent = ReferenceAgent()
    async with asyncio.TaskGroup() as owner:
        running = owner.create_task(agent.run())
        try:
            await agent.persisted.wait()
            assert agent.peak == 2
            running.cancel()
            await agent.cleanup_entered.wait()
            running.cancel()
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            pending_cleanup = not running.done() and not agent.cleaned.is_set()
            agent.release_cleanup.set()
            try:
                await running
            except asyncio.CancelledError:
                pass
        finally:
            agent.release_cleanup.set()
    interrupted = running.cancelled()
    await agent.continue_turn()
    successes = tuple(entry for entry in agent.history if entry.kind == "success")
    errors = tuple(entry for entry in agent.history if entry.kind == "error")
    recoveries = tuple(entry for entry in agent.history if entry.kind == "recovery")
    calls = tuple(entry.identity for entry in agent.history if entry.kind == "call")
    results = tuple(entry.identity for entry in agent.history if entry.kind in ("success", "error"))
    checks = {
        "parallelOverlap": agent.peak == 2,
        "callsPersistedBeforeEntry": calls == ("completed-call", "held-call"),
        "completedResultPreserved": successes == (Entry("success", "completed-call", "completed-evidence"),),
        "unfinishedOnlyReconciled": errors == (Entry("error", "held-call", "interrupted"),),
        "cancellationJoinedCleanup": pending_cleanup and agent.cleaned.is_set() and agent.active == 0,
        "interruptionPreserved": interrupted,
        "recoveryExactlyOnce": recoveries == (Entry("recovery", "assistant", "I noticed that you have interrupted me. What can I do for you?", "interrupted"),),
        "historyComplete": len(calls) == len(results) and sorted(calls) == sorted(results),
        "sameRuntimeContinuation": agent.history[-1] == Entry("reply", "assistant", "continued-after-cancellation"),
    }
    assert all(checks.values()), checks
    return checks


async def unsafe_cancellation_control() -> dict[str, bool]:
    entered, cleanup_entered, release, cleaned = (asyncio.Event() for _ in range(4))

    async def worker() -> None:
        try:
            entered.set()
            await asyncio.Event().wait()
        finally:
            cleanup_entered.set()
            await release.wait()
            cleaned.set()

    async with asyncio.TaskGroup() as owner:
        task = owner.create_task(worker())
        try:
            await entered.wait()
            task.cancel()
            without_join = not task.done() and not cleaned.is_set()
            await cleanup_entered.wait()
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            cleanup_interrupted = not cleaned.is_set()
        finally:
            release.set()
    checks = {
        "cancelWithoutJoinReturnsBeforeCleanup": without_join,
        "directRepeatedChildCancelInterruptsUnprotectedCleanup": cleanup_interrupted,
    }
    assert all(checks.values()), checks
    return checks


async def subprocess_control() -> dict[str, bool]:
    process = await asyncio.create_subprocess_exec(
        sys.executable, "-u", "-c", "import sys; print('ready', flush=True); sys.stdin.read()",
        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
    )
    try:
        assert process.stdout is not None
        assert (await process.stdout.readline()).rstrip(b"\r\n") == b"ready"
        async with asyncio.TaskGroup() as owner:
            waiting = owner.create_task(process.wait())
            await asyncio.sleep(0)
            waiting.cancel()
            try:
                await waiting
            except asyncio.CancelledError:
                pass
            still_running = process.returncode is None
    finally:
        if process.returncode is None:
            process.terminate()
        await process.wait()
        if process.stdin is not None:
            process.stdin.close()
        if process.stdout is not None:
            await process.stdout.read()
    checks = {"cancelWaitDoesNotTerminateProcess": still_running, "explicitOwnerReapedProcess": process.returncode is not None}
    assert all(checks.values()), checks
    return checks


async def main() -> None:
    async with asyncio.timeout(20):
        matched = await matched_contracts()
        negative = await unsafe_cancellation_control()
        process = await subprocess_control()
    print(json.dumps({"implementation": "Python asyncio reference", "python": platform.python_version(), "matched": matched, "negativeControls": negative, "processControl": process}, sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
