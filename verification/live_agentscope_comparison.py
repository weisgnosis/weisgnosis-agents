"""One opt-in AgentScope 2.0.9 live scenario; sanitized matched-comparison receipt."""

import argparse
import asyncio
from collections import Counter
import importlib.metadata
import json
import os
import platform
import time
from urllib.parse import urlsplit

import agentscope
from agentscope.agent import Agent, ReActConfig
from agentscope.credential import OpenAICredential
from agentscope.event import ModelCallStartEvent, ReplyEndEvent, TextBlockDeltaEvent, ToolResultEndEvent
from agentscope.message import Msg, TextBlock, ToolCallBlock, ToolResultBlock
from agentscope.model import OpenAIChatModel
from agentscope.permission import PermissionBehavior, PermissionDecision
from agentscope.tool import FunctionTool, Toolkit


SYSTEM = "You are an evidence assistant. Follow the user task exactly. Use tools when requested. After reading both values, reply with only their sum as digits."
TOOL_TASK = "Call evidence_left and evidence_right exactly once each in one batch with empty arguments, then reply with only their sum."
DESCRIPTION = "Read one independent fixed evidence number."
SCHEMA = {"type": "object", "properties": {}, "additionalProperties": False}


def blocks(agent, kind):
    return tuple(block for message in agent.state.context for block in message.content if isinstance(block, kind))


def result_text(result):
    return result.output if isinstance(result.output, str) else "".join(
        block.text for block in result.output if isinstance(block, TextBlock)
    )


def paired(agent):
    return Counter(block.id for block in blocks(agent, ToolCallBlock)) == Counter(
        block.id for block in blocks(agent, ToolResultBlock)
    )


def canonical_recoveries(agent):
    return tuple(message.model_copy(deep=True) for message in agent.state.context
                 if message.get_text_content() == agent.react_config.interruption_message
                 and message.finished_reason == "interrupted")


async def gate_or_done(gate, running, seconds):
    waiter = asyncio.create_task(gate.wait())
    try:
        async with asyncio.timeout(seconds):
            await asyncio.wait((waiter, running), return_when=asyncio.FIRST_COMPLETED)
        return gate.is_set()
    except TimeoutError:
        return False
    finally:
        waiter.cancel()
        await asyncio.gather(waiter, return_exceptions=True)


async def execute(scenario, model_id, endpoint, key):
    baseline_tasks = asyncio.all_tasks()
    run_ended = None
    tool_phase_start = None
    tool_phase_end = None
    first_visible = None
    model_requests = 0
    active = 0
    peak = 0
    entries = []
    persisted = []
    tool_tasks = set()
    held_entered, committed, cleanup_entered, release_cleanup = (asyncio.Event() for _ in range(4))
    cleaned = False
    pending_cleanup = False
    cancellation_exercised = False
    cancel_started = None
    cancel_joined = None
    boundary_active = None
    boundary_cleaned = None
    boundary_pending_tools = None
    boundary_results = None
    boundary_history_complete = None
    boundary_recoveries = None
    continuation_millis = None
    same_runtime_continuation = None
    reply = None
    reasons = []
    failure_type = None
    model = OpenAIChatModel(
        credential=OpenAICredential(api_key=key or "unauthenticated-local-gateway", base_url=endpoint[:-len("/chat/completions")]),
        model=model_id,
        parameters=OpenAIChatModel.Parameters(temperature=0, max_tokens=None, parallel_tool_calls=True),
        stream=True, max_retries=0,
        client_kwargs={"max_retries": 0, "timeout": 300},
        extra_body={"max_tokens": 4096},
    )

    def enter(name):
        nonlocal active, peak, tool_phase_start
        if tool_phase_start is None:
            tool_phase_start = time.perf_counter()
        tool_tasks.add(asyncio.current_task())
        entries.append(name)
        calls = blocks(agent, ToolCallBlock)
        persisted.append(any(call.name == name for call in calls) and
                         any({call.name for call in message.get_content_blocks("tool_call")} == {"evidence_left", "evidence_right"}
                             and len(message.get_content_blocks("tool_call")) == 2 for message in agent.state.context))
        active += 1
        peak = max(peak, active)

    async def evidence_left() -> str:
        """Read one independent fixed evidence number."""
        nonlocal active, tool_phase_end
        enter("evidence_left")
        try:
            await asyncio.sleep(0.5)
            return "17"
        finally:
            active -= 1
            if active == 0:
                tool_phase_end = time.perf_counter()

    async def evidence_right() -> str:
        """Read one independent fixed evidence number."""
        nonlocal active, cleaned, tool_phase_end
        enter("evidence_right")
        held_entered.set()
        try:
            if scenario == "cancel":
                await asyncio.Event().wait()
            else:
                await asyncio.sleep(0.5)
            return "20"
        finally:
            if scenario == "cancel":
                cleanup_entered.set()
                await release_cleanup.wait()
                cleaned = True
            active -= 1
            if active == 0:
                tool_phase_end = time.perf_counter()

    try:
        permission = PermissionDecision(behavior=PermissionBehavior.ALLOW, message="Host permits fixed evidence reads")
        tools = [] if scenario == "text" else [
            FunctionTool(tool, description=DESCRIPTION, input_schema=SCHEMA,
                         is_concurrency_safe=scenario != "sequential", is_read_only=True, permission=permission)
            for tool in (evidence_left, evidence_right)
        ]
        agent = Agent(name="live-comparison", system_prompt=SYSTEM, model=model,
                      toolkit=Toolkit(tools=tools), react_config=ReActConfig(max_iters=3,
                          interruption_message="I noticed that you have interrupted me. What can I do for you?"))
    except BaseException:
        await model.client.close()
        raise

    async def consume():
        nonlocal first_visible, model_requests, reply
        async for item in agent.reply_stream(
            Msg(name="user", role="user", content=[TextBlock(text="Reply with exactly READY." if scenario == "text" else TOOL_TASK)]),
            yield_final_msg=True,
        ):
            if isinstance(item, ModelCallStartEvent):
                model_requests += 1
            if isinstance(item, TextBlockDeltaEvent) and item.delta and first_visible is None:
                first_visible = (time.perf_counter() - start) * 1000
            if isinstance(item, ReplyEndEvent):
                reasons.append(str(item.finished_reason))
            if isinstance(item, Msg):
                reply = item
            if isinstance(item, ToolResultEndEvent) and scenario == "cancel":
                if any(result.id == item.tool_call_id and result.name == "evidence_left"
                       and result.state == "success" and result_text(result) == "17"
                       for result in blocks(agent, ToolResultBlock)):
                    committed.set()

    start = time.perf_counter()
    running = asyncio.create_task(consume())
    try:
        async with asyncio.timeout(300):
            if scenario == "cancel":
                ready = await gate_or_done(committed, running, 295)
                if ready and held_entered.is_set() and len(entries) == 2 and all(persisted):
                    cancellation_exercised = True
                    cancel_started = time.perf_counter()
                    running.cancel()
                    if await gate_or_done(cleanup_entered, running, 5):
                        running.cancel()
                        await asyncio.sleep(0)
                        await asyncio.sleep(0.1)
                        pending_cleanup = not running.done() and not cleaned
                        release_cleanup.set()
                elif not running.done():
                    failure_type = "CancellationPreconditionNotExercised"
                    running.cancel()
                    release_cleanup.set()
            try:
                await running
            except asyncio.CancelledError:
                if asyncio.current_task().cancelling() or not cancellation_exercised:
                    raise
            boundary_active = active
            boundary_cleaned = cleaned
            boundary_pending_tools = sum(not task.done() for task in tool_tasks)
            boundary_results = tuple(result.model_copy(deep=True) for result in blocks(agent, ToolResultBlock))
            boundary_history_complete = paired(agent)
            boundary_recoveries = canonical_recoveries(agent)
            if cancel_started is not None:
                cancel_joined = (time.perf_counter() - cancel_started) * 1000
            run_ended = time.perf_counter()
            if cancellation_exercised and running.done() and boundary_pending_tools == 0:
                before_continuation = tuple(entries)
                continuation_start = time.perf_counter()
                continuation_reply = None
                async for item in agent.reply_stream(
                    Msg(name="user", role="user", content=[TextBlock(text="Reply with exactly READY.")]),
                    yield_final_msg=True,
                ):
                    if isinstance(item, ModelCallStartEvent):
                        model_requests += 1
                    if isinstance(item, Msg):
                        continuation_reply = item
                continuation_millis = (time.perf_counter() - continuation_start) * 1000
                same_runtime_continuation = continuation_reply is not None and (continuation_reply.get_text_content() or "").strip() == "READY" \
                    and continuation_reply.finished_reason == "completed" and tuple(entries) == before_continuation \
                    and tuple(blocks(agent, ToolResultBlock)) == boundary_results \
                    and canonical_recoveries(agent) == boundary_recoveries and paired(agent)
    except asyncio.CancelledError:
        if not cancellation_exercised:
            failure_type = "UnexpectedCancellation"
    except Exception as error:
        failure_type = type(error).__name__
    finally:
        if run_ended is None:
            run_ended = time.perf_counter()
        if boundary_active is None:
            boundary_active = active
            boundary_cleaned = cleaned
            boundary_pending_tools = sum(not task.done() for task in tool_tasks)
        if boundary_results is None:
            boundary_results = tuple(result.model_copy(deep=True) for result in blocks(agent, ToolResultBlock))
            boundary_history_complete = paired(agent)
        if boundary_recoveries is None:
            boundary_recoveries = canonical_recoveries(agent)
        if cancel_started is not None and running.done() and cancel_joined is None:
            cancel_joined = (time.perf_counter() - cancel_started) * 1000
        # Join fixture work after recording cancellation behavior; release cannot remain withheld.
        release_cleanup.set()
        if not running.done():
            running.cancel()
        pending = asyncio.all_tasks() - baseline_tasks - {asyncio.current_task()}
        if pending:
            _, remaining = await asyncio.wait(pending, timeout=5)
            for task in remaining:
                task.cancel()
            if remaining:
                _, stubborn = await asyncio.wait(remaining, timeout=2)
                if stubborn:
                    failure_type = "HarnessCleanupTimeout"
        await model.client.close()

    total = (run_ended - start) * 1000
    results = boundary_results
    completed_preserved = sum(result.name == "evidence_left" and result.state == "success"
                              and result_text(result) == "17" for result in results) == 1
    unfinished_reconciled = len(results) == 2 and sum(result.name == "evidence_right" and result.state in ("error", "interrupted")
                                                   for result in results) == 1
    exact_reply = reply is not None and (reply.get_text_content() or "").strip() == ("READY" if scenario == "text" else "37")
    expected_peak = 0 if scenario == "text" else 1 if scenario == "sequential" else 2
    usage = reply.usage.model_dump() if reply is not None and reply.usage is not None else None
    recovery_once = len(boundary_recoveries) == 1
    correct_results = len(results) == 2 and {result.name for result in results} == {"evidence_left", "evidence_right"} \
        and all(result.state == "success" and result_text(result) ==
                                               {"evidence_left": "17", "evidence_right": "20"}.get(result.name)
                                               for result in results)
    passed = failure_type is None and (
        cancellation_exercised and pending_cleanup and boundary_cleaned and boundary_active == 0 and completed_preserved
        and unfinished_reconciled and boundary_history_complete and "interrupted" in reasons and recovery_once and same_runtime_continuation
        if scenario == "cancel" else
        exact_reply and "completed" in reasons and peak == expected_peak and active == 0
        and (scenario == "text" or len(entries) == 2 and all(persisted) and boundary_history_complete and correct_results)
    )
    return {
        "implementation": "Python AgentScope", "agentscope": importlib.metadata.version("agentscope"),
        "python": platform.python_version(), "model": model_id, "scenario": scenario,
        "status": "Passed" if passed else "Failed", "scenarioAccepted": bool(passed), "errorType": failure_type,
        "state": reasons[-1] if reasons else "no-terminal-event",
        "firstVisibleMillis": first_visible, "totalMillis": total, "modelRequests": model_requests,
        "toolPhaseMillis": (tool_phase_end - tool_phase_start) * 1000 if scenario in ("sequential", "parallel")
                           and tool_phase_start is not None and tool_phase_end is not None else None,
        "peakActive": peak, "executions": entries, "activeTools": boundary_active,
        "callsPersistedBeforeEntry": all(persisted) if len(entries) == 2 and Counter(entries) == Counter(("evidence_left", "evidence_right")) else None,
        "historyComplete": boundary_history_complete if results or blocks(agent, ToolCallBlock) or reply is not None else None, "expectedReply": exact_reply, "usage": usage,
        "cancellationExercised": cancellation_exercised if scenario == "cancel" else None,
        "cancellationJoinedCleanup": pending_cleanup and boundary_cleaned and boundary_active == 0 if cancellation_exercised else None,
        "completedResultPreserved": completed_preserved if cancellation_exercised else None,
        "unfinishedOnlyReconciled": unfinished_reconciled if cancellation_exercised else None,
        "interruptionPreserved": "interrupted" in reasons or running.cancelled() if cancellation_exercised else None,
        "recoveryExactlyOnce": recovery_once if cancellation_exercised else None,
        "cancelJoinedMillis": cancel_joined,
        "continuationMillis": continuation_millis,
        "sameRuntimeContinuation": same_runtime_continuation,
        "pendingToolTasksAtBoundary": boundary_pending_tools,
        "failureCategory": "model-profile-not-exercised" if scenario == "cancel" and not cancellation_exercised else
                           "timeout" if failure_type == "TimeoutError" else "provider-or-runtime" if failure_type else
                           "runtime-or-model-oracle" if not passed else None,
        "localSchedulingPolicy": "tool-concurrency-safety-false" if scenario == "sequential" else "concurrency-safe",
    }


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenario", choices=("text", "sequential", "parallel", "cancel"))
    scenario = parser.parse_args().scenario
    agentscope.logger.disabled = True
    version = importlib.metadata.version("agentscope")
    if version != "2.0.9":
        raise RuntimeError("Run with the isolated pinned AgentScope 2.0.9 interpreter")
    enabled = os.environ.get("WEISGNOSIS_LIVE_SMOKE")
    if enabled is None:
        receipt = {"implementation": "Python AgentScope", "scenario": scenario, "status": "NotRun", "scenarioAccepted": False, "reason": "Live opt-in disabled"}
    else:
        endpoint = os.environ.get("WEISGNOSIS_LIVE_ENDPOINT", "")
        model = os.environ.get("WEISGNOSIS_LIVE_MODEL", "")
        parsed = urlsplit(endpoint)
        if enabled != "enabled" or not model or len(model) > 256 or parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.query or parsed.fragment or not endpoint.endswith("/chat/completions"):
            receipt = {"implementation": "Python AgentScope", "scenario": scenario, "status": "Failed", "scenarioAccepted": False, "errorType": "InvalidLiveConfiguration", "failureCategory": "configuration"}
        else:
            try:
                receipt = await execute(scenario, model, endpoint, os.environ.get("WEISGNOSIS_LIVE_API_KEY"))
            except Exception as error:
                receipt = {"implementation": "Python AgentScope", "model": model, "scenario": scenario,
                           "status": "Failed", "scenarioAccepted": False, "errorType": type(error).__name__, "failureCategory": "fixture-setup-or-cleanup"}
    print(json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
