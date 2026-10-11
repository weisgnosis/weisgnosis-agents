"""Pinned AgentScope public-runtime comparison; scripted model, no network or fixes."""

import asyncio
from collections import Counter
import importlib.metadata
import json
import platform

from agentscope.agent import Agent, ReActConfig
from agentscope.credential import OpenAICredential
from agentscope.event import ReplyEndEvent, ToolResultEndEvent
from agentscope.formatter import OpenAIChatFormatter
from agentscope.message import Msg, TextBlock, ToolCallBlock, ToolResultBlock
from agentscope.model import ChatModelBase, ChatResponse
from agentscope.permission import PermissionBehavior, PermissionDecision
from agentscope.tool import FunctionTool, Toolkit


VERSION = "2.0.9"
RECOVERY = "I noticed that you have interrupted me. What can I do for you?"
EXPECTED_CALLS = ("completed-call", "held-call")


class ScriptedModel(ChatModelBase):
    """Use the documented model extension point; never construct a remote client."""

    def __init__(self) -> None:
        super().__init__(
            credential=OpenAICredential(api_key="offline-placeholder"),
            model="scripted-offline", parameters=self.Parameters(),
            stream=False, max_retries=0,
        )
        self.formatter = OpenAIChatFormatter()
        self.requests = 0

    async def _call_api(self, model_name, messages, tools=None, tool_choice=None, **kwargs):
        self.requests += 1
        content = (
            [ToolCallBlock(id="completed-call", name="completed_read", input="{}"),
             ToolCallBlock(id="held-call", name="held_read", input="{}")]
            if self.requests == 1
            else [TextBlock(text="continued-after-cancellation")]
        )
        return ChatResponse(content=content, is_last=True)


def blocks(agent, block_type):
    return tuple(block for message in agent.state.context
                 for block in message.content if isinstance(block, block_type))


def output_text(result):
    return result.output if isinstance(result.output, str) else "".join(
        block.text for block in result.output if isinstance(block, TextBlock)
    )


def complete_history(agent):
    return Counter(block.id for block in blocks(agent, ToolCallBlock)) == Counter(
        block.id for block in blocks(agent, ToolResultBlock)
    )


async def wait_gate_or_completion(gate, running):
    waiter = asyncio.create_task(gate.wait())
    try:
        async with asyncio.timeout(5):
            await asyncio.wait((waiter, running), return_when=asyncio.FIRST_COMPLETED)
        return gate.is_set()
    except TimeoutError:
        return False
    finally:
        waiter.cancel()
        await asyncio.gather(waiter, return_exceptions=True)


async def arm(label, repeated, propagate):
    baseline = asyncio.all_tasks()
    held_entered, committed, cleanup_entered, release_cleanup = (
        asyncio.Event() for _ in range(4)
    )
    active = 0
    peak = 0
    entries = []
    persisted_at_entry = []
    tool_tasks = set()
    cleaned = False
    events = []
    delivered = []
    model = ScriptedModel()

    def enter(name):
        nonlocal active, peak
        tool_tasks.add(asyncio.current_task())
        entries.append(name)
        persisted_at_entry.append(tuple(block.id for block in blocks(agent, ToolCallBlock)) == EXPECTED_CALLS)
        active += 1
        peak = max(peak, active)

    async def completed_read() -> str:
        """Read fixed completed evidence."""
        nonlocal active
        enter("completed_read")
        try:
            await held_entered.wait()
            return "completed-evidence"
        finally:
            active -= 1

    async def held_read() -> str:
        """Hold until the host cancels, then wait for cleanup release."""
        nonlocal active, cleaned
        enter("held_read")
        held_entered.set()
        try:
            await asyncio.Event().wait()
            return "unreachable"
        finally:
            cleanup_entered.set()
            await release_cleanup.wait()
            cleaned = True
            active -= 1

    permission = PermissionDecision(behavior=PermissionBehavior.ALLOW, message="Host permits the two fixed evidence reads")
    toolkit = Toolkit(tools=[
        FunctionTool(tool, is_concurrency_safe=True, is_read_only=True, permission=permission)
        for tool in (completed_read, held_read)
    ])
    agent = Agent(
        name="synthetic-comparison", system_prompt="Run the scripted evidence task.",
        model=model, toolkit=toolkit,
        react_config=ReActConfig(max_iters=2, interruption_raise_cancelled_error=propagate,
                                interruption_message=RECOVERY),
    )

    async def consume():
        async for item in agent.reply_stream(
            Msg(name="user", role="user", content=[TextBlock(text="Run the two evidence reads")]),
            yield_final_msg=True,
        ):
            if isinstance(item, Msg):
                delivered.append(item)
            else:
                events.append(item)
            if isinstance(item, ToolResultEndEvent) and item.tool_call_id == "completed-call":
                if any(result.id == "completed-call" and result.state == "success"
                       and output_text(result) == "completed-evidence"
                       for result in blocks(agent, ToolResultBlock)):
                    committed.set()

    running = asyncio.create_task(consume())
    ready = False
    cleanup_ready = False
    pending_cleanup = False
    pending_workers_at_boundary = None
    task_cancelled = False
    task_error = None
    continuation_ok = None
    try:
        ready = await wait_gate_or_completion(committed, running)
        if ready:
            running.cancel()
            cleanup_ready = await wait_gate_or_completion(cleanup_entered, running)
            if cleanup_ready:
                if repeated:
                    running.cancel()
                await asyncio.sleep(0)
                await asyncio.sleep(0)
                pending_cleanup = not running.done() and not cleaned
                release_cleanup.set()
        # Bounded observation does not cancel or repair framework work.
        await asyncio.wait((running,), timeout=5)
        if running.done():
            task_cancelled = running.cancelled()
            if not task_cancelled and running.exception() is not None:
                task_error = type(running.exception()).__name__
                if not ready:
                    raise RuntimeError("Comparison fixture failed before persisted tool completion") from running.exception()
            pending_workers_at_boundary = sum(not task.done() for task in tool_tasks)
        else:
            task_error = "FrameworkBoundaryTimeout"
            pending_workers_at_boundary = sum(not task.done() for task in tool_tasks)

        results = tuple(result.model_copy(deep=True) for result in blocks(agent, ToolResultBlock))
        original_history_complete = complete_history(agent)
        recoveries = tuple(message for message in agent.state.context
                           if message.get_text_content() == RECOVERY
                           and message.finished_reason == "interrupted")
        reply_ends = tuple(str(event.finished_reason) for event in events if isinstance(event, ReplyEndEvent))
        delivered_recovery = tuple(message for message in delivered
                                   if message.get_text_content() == RECOVERY
                                   and message.finished_reason == "interrupted")
        joined_at_boundary = cleaned and active == 0 and pending_workers_at_boundary == 0
        initial_entries = tuple(entries)
        initial_requests = model.requests
        if ready and running.done() and pending_workers_at_boundary == 0:
            try:
                async with asyncio.timeout(5):
                    continued = await agent.reply(Msg(
                        name="user", role="user", content=[TextBlock(text="Continue without repeating evidence reads")]
                    ))
                continuation_ok = (
                    continued.get_text_content() == "continued-after-cancellation"
                    and continued.finished_reason == "completed"
                    and tuple(entries) == initial_entries
                    and model.requests == initial_requests + 1
                    and complete_history(agent)
                    and sum(message.get_text_content() == RECOVERY for message in agent.state.context) == len(recoveries)
                )
            except (Exception, asyncio.CancelledError) as error:
                continuation_ok = False
                task_error = task_error or type(error).__name__

        matched = {
            "parallelOverlap": peak == 2 if ready else None,
            "callsPersistedBeforeEntry": all(persisted_at_entry) and len(persisted_at_entry) == 2 if ready else None,
            "completedResultPreserved": sum(result.id == "completed-call" and result.state == "success"
                                            and output_text(result) == "completed-evidence" for result in results) == 1 if ready else None,
            "unfinishedOnlyReconciled": len(results) == 2 and sum(result.id == "held-call" and result.state in ("error", "interrupted")
                                                                for result in results) == 1 if ready else None,
            "cancellationJoinedCleanup": cleanup_ready and pending_cleanup and joined_at_boundary if ready else None,
            "interruptionPreserved": task_cancelled or "interrupted" in reply_ends or bool(delivered_recovery) if ready else None,
            "recoveryExactlyOnce": len(recoveries) == 1 if ready else None,
            "historyComplete": original_history_complete if ready else None,
            "sameRuntimeContinuation": continuation_ok,
        }
        return {
            "arm": label, "matched": matched,
            "oracleStatus": {name: "NotExercised" if value is None else "Passed" if value else "Failed"
                             for name, value in matched.items()},
            "observed": {
                "taskCancelled": task_cancelled, "taskErrorType": task_error,
                "replyEndReasons": reply_ends, "deliveredRecoveryCount": len(delivered_recovery),
                "canonicalRecoveryCount": len(recoveries), "toolResultStates": {result.id: str(result.state) for result in results},
                "activeToolsAtBoundary": active, "pendingToolTasksAtBoundary": pending_workers_at_boundary,
                "cleanupCompletedAtBoundary": cleaned, "peakActive": peak,
            },
        }
    finally:
        # Emergency harness ownership starts after framework-boundary measurements.
        release_cleanup.set()
        pending = asyncio.all_tasks() - baseline - {asyncio.current_task()}
        if pending:
            done, remaining = await asyncio.wait(pending, timeout=3)
            for task in remaining:
                task.cancel()
            if remaining:
                done_again, stubborn = await asyncio.wait(remaining, timeout=2)
                done |= done_again
                if stubborn:
                    raise RuntimeError("Comparison harness could not join remaining framework tasks")
            for task in done:
                if not task.cancelled():
                    task.exception()


async def main():
    actual = importlib.metadata.version("agentscope")
    if actual != VERSION:
        raise RuntimeError(f"Expected isolated AgentScope {VERSION}, found {actual}")
    receipts = []
    for label, repeated, propagate in (
        ("default-single-cancel", False, False),
        ("default-repeated-cancel", True, False),
        ("configured-propagation-repeated-cancel", True, True),
    ):
        async with asyncio.timeout(20):
            receipts.append(await arm(label, repeated, propagate))
    print(json.dumps({"implementation": "Python AgentScope", "agentscope": actual,
                      "python": platform.python_version(), "receipts": receipts}, sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
