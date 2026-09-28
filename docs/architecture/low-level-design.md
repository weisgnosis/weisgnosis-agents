# Low-Level Technical Specification (LLD)

> **Document:** Low-Level Technical Specification (LLD)  
> **System:** `weisgnosis-agents`  
> **Namespace:** `com.weisgnosis.agents`  
> **Platform:** Scala 3.9.0 LTS, ZIO 2.1.26+, zio-json 0.9.1+  
> **License:** Apache-2.0  
> **Status:** Approved / Living Document  

---

## 1. Domain Model Specification (`com.weisgnosis.agents.core.model`)

### 1.1. Roles & Tool Execution States
```scala
package com.weisgnosis.agents.core.model

import zio.json.*

enum Role:
  case System, User, Assistant, Tool

object Role:
  given JsonCodec[Role] = JsonCodec.string.transformOrFail(
    {
      case "system"    => Right(Role.System)
      case "user"      => Right(Role.User)
      case "assistant" => Right(Role.Assistant)
      case "tool"      => Right(Role.Tool)
      case other       => Left(s"Invalid role: $other")
    },
    {
      case Role.System    => "system"
      case Role.User      => "user"
      case Role.Assistant => "assistant"
      case Role.Tool      => "tool"
    }
  )

enum ToolResultState:
  case Success, Error, Interrupted, Denied

object ToolResultState:
  given JsonCodec[ToolResultState] = JsonCodec.string.transformOrFail(
    {
      case "success"     => Right(ToolResultState.Success)
      case "error"       => Right(ToolResultState.Error)
      case "interrupted" => Right(ToolResultState.Interrupted)
      case "denied"      => Right(ToolResultState.Denied)
      case other         => Left(s"Invalid tool result state: $other")
    },
    {
      case ToolResultState.Success     => "success"
      case ToolResultState.Error       => "error"
      case ToolResultState.Interrupted => "interrupted"
      case ToolResultState.Denied      => "denied"
    }
  )
```

### 1.2. Content Blocks ADT
```scala
package com.weisgnosis.agents.core.model

import zio.json.*
import zio.json.ast.Json

sealed trait ContentBlock

object ContentBlock:
  final case class Text(
    text: String
  ) extends ContentBlock

  final case class Thinking(
    thought: String,
    signature: Option[String] = None,
    metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock

  final case class ToolUse(
    id: String,
    name: String,
    input: Json,
    isServerTool: Boolean = false,
    thoughtSignature: Option[String] = None,
    metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock

  final case class ToolResult(
    toolUseId: String,
    name: String,
    output: String,
    state: ToolResultState = ToolResultState.Success
  ) extends ContentBlock

  final case class Image(
    url: Option[String] = None,
    data: Option[String] = None,
    mediaType: String = "image/png"
  ) extends ContentBlock

  final case class Custom(
    kind: String,
    payload: Json
  ) extends ContentBlock

  given JsonCodec[Text] = DeriveJsonCodec.gen
  given JsonCodec[Thinking] = DeriveJsonCodec.gen
  given JsonCodec[ToolUse] = DeriveJsonCodec.gen
  given JsonCodec[ToolResult] = DeriveJsonCodec.gen
  given JsonCodec[Image] = DeriveJsonCodec.gen
  given JsonCodec[Custom] = DeriveJsonCodec.gen

  given JsonCodec[ContentBlock] =
    JsonCodec.json.transformOrFail(
      json => {
        val obj = json.asObject.getOrElse(JsonObject.empty)
        obj.get("type").flatMap(_.asString) match {
          case Some("text")        => json.as[Text]
          case Some("thinking")    => json.as[Thinking]
          case Some("tool_use")    => json.as[ToolUse]
          case Some("tool_result") => json.as[ToolResult]
          case Some("image")       => json.as[Image]
          case Some("custom")      => json.as[Custom]
          case other               => Left(s"Unknown content block type: $other")
        }
      },
      {
        case b: Text       => b.toJsonAST.map(_.mapObject(_.add("type", Json.Str("text")))).getOrElse(Json.Null)
        case b: Thinking   => b.toJsonAST.map(_.mapObject(_.add("type", Json.Str("thinking")))).getOrElse(Json.Null)
        case b: ToolUse    => b.toJsonAST.map(_.mapObject(_.add("type", Json.Str("tool_use")))).getOrElse(Json.Null)
        case b: ToolResult => b.toJsonAST.map(_.mapObject(_.add("type", Json.Str("tool_result")))).getOrElse(Json.Null)
        case b: Image      => b.toJsonAST.map(_.mapObject(_.add("type", Json.Str("image")))).getOrElse(Json.Null)
        case b: Custom     => b.toJsonAST.map(_.mapObject(_.add("type", Json.Str("custom")))).getOrElse(Json.Null)
      }
    )
```

### 1.3. Message Structure (`Msg`)
```scala
package com.weisgnosis.agents.core.model

import zio.Chunk
import zio.json.*
import java.util.UUID

final case class Msg(
  id: String = UUID.randomUUID().toString,
  role: Role,
  content: Chunk[ContentBlock],
  name: Option[String] = None,
  metadata: Map[String, String] = Map.empty,
  timestamp: Long = System.currentTimeMillis()
):
  def text: String =
    content.collect { case ContentBlock.Text(t) => t }.mkString("\n")

  def thinking: String =
    content.collect { case ContentBlock.Thinking(t, _, _) => t }.mkString("\n")

  def toolUses: Chunk[ContentBlock.ToolUse] =
    content.collect { case tu: ContentBlock.ToolUse => tu }

  def toolResults: Chunk[ContentBlock.ToolResult] =
    content.collect { case tr: ContentBlock.ToolResult => tr }

object Msg:
  given JsonCodec[Msg] = DeriveJsonCodec.gen

  def user(text: String): Msg =
    Msg(role = Role.User, content = Chunk(ContentBlock.Text(text)))

  def assistant(text: String): Msg =
    Msg(role = Role.Assistant, content = Chunk(ContentBlock.Text(text)))

  def system(text: String): Msg =
    Msg(role = Role.System, content = Chunk(ContentBlock.Text(text)))

  def systemReminder(text: String): Msg =
    Msg(
      role = Role.User,
      name = Some("system"),
      content = Chunk(ContentBlock.Text(s"<system-reminder>$text</system-reminder>")),
      metadata = Map("synthetic" -> "true", "reminder_kind" -> "system_directive")
    )
```

---

## 2. Event Specification: The 31 Typed Events (`com.weisgnosis.agents.core.event`)

```scala
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.model.ToolResultState
import zio.json.*
import zio.json.ast.Json

sealed trait AgentEvent:
  def source: Option[String]
  def withSource(src: String): AgentEvent

object AgentEvent:
  // 1. Agent Lifecycle
  final case class AgentStartEvent(sessionId: String, replyId: String, agentId: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class AgentEndEvent(replyId: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class AgentProgressEvent(message: String, currentStep: Int, totalSteps: Option[Int] = None, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 2. Text Streaming Deltas
  final case class TextBlockStartEvent(blockIndex: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class TextBlockDeltaEvent(delta: String, blockIndex: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class TextBlockEndEvent(blockIndex: Int, fullText: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 3. Reasoning / Thinking Deltas
  final case class ThinkingBlockStartEvent(blockIndex: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ThinkingBlockDeltaEvent(delta: String, blockIndex: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ThinkingBlockEndEvent(blockIndex: Int, fullThought: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 4. Tool Calls (with streamIndex support for parallel interleaving)
  final case class ToolCallStartEvent(callId: String, name: String, blockIndex: Int, streamIndex: Option[Int] = None, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolCallDeltaEvent(callId: String, delta: String, blockIndex: Int, streamIndex: Option[Int] = None, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolCallEndEvent(callId: String, name: String, arguments: Json, blockIndex: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 5. Tool Results
  final case class ToolResultStartEvent(callId: String, name: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolResultDeltaEvent(callId: String, delta: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolResultEndEvent(callId: String, name: String, output: String, state: ToolResultState, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 6. Raw Model Invocations
  final case class ModelRequestStartEvent(model: String, promptTokens: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ModelResponseChunkEvent(chunk: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ModelResponseEndEvent(model: String, completionTokens: Int, totalTokens: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 7. Subagent Operations
  final case class SubagentSpawnEvent(parentSessionId: String, childSessionId: String, agentId: String, label: Option[String], source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class SubagentStartEvent(childSessionId: String, agentId: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class SubagentEndEvent(childSessionId: String, agentId: String, status: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class SubagentExposedEvent(subagentId: String, agentId: String, sessionId: String, label: Option[String], source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 8. Permissions & HITL
  final case class PermissionAskEvent(requestId: String, toolName: String, input: Json, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class PermissionDecisionEvent(requestId: String, allowed: Boolean, reason: Option[String], source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 9. Memory & Compaction
  final case class CompactionStartEvent(triggerTokens: Int, messageCount: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class CompactionEndEvent(evictedTokens: Int, remainingMessages: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class HintBlockEvent(hint: String, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 10. Errors & Extensibility
  final case class ErrorEvent(message: String, code: Option[String] = None, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class CustomEvent(kind: String, payload: Json, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))
```

---

## 3. Tool Engine & Registry (`com.weisgnosis.agents.core.tool`)

```scala
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.*
import zio.json.ast.Json

final case class ToolDef(
  name: String,
  description: String,
  parametersSchema: Json
)

trait Tool[-R, In, +Out]:
  def definition: ToolDef
  def isConcurrencySafe: Boolean = true
  def execute(input: In): ZIO[R, Throwable, Out]
  def decodeInput(raw: Json): Either[String, In]
  def encodeOutput(out: Out): String

final case class ToolExecutionResult(
  output: String,
  state: ToolResultState
)

final case class Toolkit(
  tools: Map[String, Tool[Any, ?, ?]] = Map.empty
):
  def register(tool: Tool[Any, ?, ?]): Toolkit =
    require(!tools.contains(tool.definition.name), s"Tool collision: ${tool.definition.name}")
    copy(tools = tools + (tool.definition.name -> tool))

  def ++(other: Toolkit): Toolkit =
    copy(tools = tools ++ other.tools)

  def get(name: String): Option[Tool[Any, ?, ?]] =
    tools.get(name)

  def definitions: Chunk[ToolDef] =
    Chunk.fromIterable(tools.values.map(_.definition))

  def executeSafe(name: String, rawArgs: Json): ZIO[Any, Nothing, ToolExecutionResult] =
    tools.get(name) match {
      case None =>
        ZIO.succeed(ToolExecutionResult(s"Tool '$name' not found in toolkit.", ToolResultState.Error))
      case Some(tool) =>
        tool.decodeInput(rawArgs) match {
          case Left(decodeErr) =>
            ZIO.succeed(ToolExecutionResult(s"Invalid arguments for '$name': $decodeErr", ToolResultState.Error))
          case Right(typedIn) =>
            tool.asInstanceOf[Tool[Any, Any, Any]].execute(typedIn)
              .map(out => ToolExecutionResult(tool.asInstanceOf[Tool[Any, Any, Any]].encodeOutput(out), ToolResultState.Success))
              .catchAll(err =>
                ZIO.succeed(ToolExecutionResult(s"Execution failed for '$name': ${err.getMessage}", ToolResultState.Error))
              )
        }
    }
```

---

## 4. ReAct Engine Implementation (`com.weisgnosis.agents.core.agent`)

```scala
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.LlmClient
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Toolkit, ToolExecutionResult}
import zio.*

final case class AgentConfig(
  name: String,
  systemPrompt: String,
  maxIterations: Int = 20,
  timeout: Duration = 300.seconds,
  emptyThinkingRetryLimit: Int = 2
)

final case class AgentResult(
  reply: Msg,
  history: Chunk[Msg],
  iterationCount: Int,
  isSuspended: Boolean = false
)

object ReActAgent:
  def step(
    config: AgentConfig,
    toolkit: Toolkit,
    client: LlmClient,
    historyRef: Ref[Chunk[Msg]],
    iteration: Int,
    emptyThinkingRetries: Int,
    eventHub: Hub[AgentEvent]
  ): ZIO[Any, AgentError, AgentResult] =
    if iteration > config.maxIterations then
      ZIO.fail(AgentError.MaxIterationsExceeded(config.maxIterations))
    else
      ZIO.uninterruptibleMask { restore =>
        for {
          history <- historyRef.get
          
          // 1. Stream from Model Client
          streamResult <- restore(client.streamTurn(history, toolkit.definitions, config, eventHub))

          result <- streamResult match {
            // Case A: Pure Text Final Answer
            case TurnOutput.FinalText(assistantMsg) =>
              for {
                _ <- historyRef.update(_ :+ assistantMsg)
                finalHist <- historyRef.get
              } yield AgentResult(assistantMsg, finalHist, iteration)

            // Case B: Thinking Only (Loopback Reminder)
            case TurnOutput.ThinkingOnly(thinkingMsg) if emptyThinkingRetries < config.emptyThinkingRetryLimit =>
              val reminder = Msg.systemReminder("Your previous reply had empty content - the full answer was written to the reasoning channel only. Reply again and write the final answer into the content channel.")
              for {
                _ <- historyRef.update(_ :+ thinkingMsg :+ reminder)
                nextTurn <- step(config, toolkit, client, historyRef, iteration + 1, emptyThinkingRetries + 1, eventHub)
              } yield nextTurn

            case TurnOutput.ThinkingOnly(_) =>
              ZIO.fail(AgentError.EmptyThinkingError("Exceeded retry limit for thinking-only turns without content."))

            // Case C: Tool Calls
            case TurnOutput.ToolCalls(assistantMsg, calls) =>
              for {
                // Invariant: Save assistant turn to Ref immediately before tool execution
                _ <- historyRef.update(_ :+ assistantMsg)
                
                // Partition parallel vs sequential tools
                (parallelCalls, serialCalls) = calls.partition(c => toolkit.get(c.name).exists(_.isConcurrencySafe))
                
                parallelResults <- restore(ZIO.foreachPar(parallelCalls) { call =>
                  executeAndRecord(call, toolkit, historyRef, eventHub)
                })
                
                serialResults <- restore(ZIO.foreach(serialCalls) { call =>
                  executeAndRecord(call, toolkit, historyRef, eventHub)
                })
                
                nextTurn <- step(config, toolkit, client, historyRef, iteration + 1, 0, eventHub)
              } yield nextTurn
          }
        } yield result
      }.onInterrupt {
        // Dangling Tool Reconciliation on Fiber Cancellation
        reconcileDanglingToolCalls(historyRef)
      }

  private def executeAndRecord(
    call: ContentBlock.ToolUse,
    toolkit: Toolkit,
    historyRef: Ref[Chunk[Msg]],
    eventHub: Hub[AgentEvent]
  ): ZIO[Any, Nothing, Unit] =
    for {
      _ <- eventHub.publish(AgentEvent.ToolResultStartEvent(call.id, call.name))
      res <- toolkit.executeSafe(call.name, call.input)
      resultBlock = ContentBlock.ToolResult(call.id, call.name, res.output, res.state)
      _ <- eventHub.publish(AgentEvent.ToolResultEndEvent(call.id, call.name, res.output, res.state))
      _ <- historyRef.update(_ :+ Msg(role = Role.Tool, content = Chunk(resultBlock)))
    } yield ()

  private def reconcileDanglingToolCalls(historyRef: Ref[Chunk[Msg]]): UIO[Unit] =
    historyRef.update { history =>
      val existingResultIds = history.flatMap(_.toolResults).map(_.toolUseId).toSet
      val lastAssistant = history.reverse.find(_.role == Role.Assistant)
      
      lastAssistant match {
        case None => history
        case Some(asst) =>
          val pendingCalls = asst.toolUses.filterNot(tu => tu.isServerTool || existingResultIds.contains(tu.id))
          if pendingCalls.isEmpty then history
          else
            val synthesizedResults = pendingCalls.map { call =>
              Msg(
                role = Role.Tool,
                content = Chunk(ContentBlock.ToolResult(
                  call.id,
                  call.name,
                  s"Tool execution was interrupted before it could run. Tool: ${call.name}",
                  ToolResultState.Interrupted
                ))
              )
            }
            val recoveryMsg = Msg.assistant("I noticed that you have interrupted me. What can I do for you?")
            history ++ synthesizedResults :+ recoveryMsg
      }
    }
```

---

## 5. Permission Engine & HITL (`com.weisgnosis.agents.harness.permission`)

```scala
package com.weisgnosis.agents.harness.permission

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

enum PermissionBehavior derives JsonCodec:
  case Allow, Deny, Ask, Passthrough

final case class PermissionRule(
  toolName: String,
  ruleContent: Option[String] = None,
  behavior: PermissionBehavior,
  source: String = "user"
)

final case class ConfirmResult(
  confirmed: Boolean,
  modifiedInput: Option[Json] = None,
  newRules: Chunk[PermissionRule] = Chunk.empty,
  reason: Option[String] = None
)

final case class PermissionEngine(
  denyRules: Map[String, Chunk[PermissionRule]] = Map.empty,
  askRules: Map[String, Chunk[PermissionRule]] = Map.empty,
  allowRules: Map[String, Chunk[PermissionRule]] = Map.empty
):
  def evaluate(toolName: String, input: Json): PermissionBehavior =
    // Strict 6-Stage Waterfall
    if denyRules.contains(toolName) then PermissionBehavior.Deny
    else if askRules.contains(toolName) then PermissionBehavior.Ask
    else if allowRules.contains(toolName) then PermissionBehavior.Allow
    else PermissionBehavior.Ask // Default safe fallback
```

---

## 6. Context Engineering & Memory ADTs (`com.weisgnosis.agents.harness.memory`)

```scala
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.model.Msg
import zio.Chunk

final case class CompactorConfig(
  triggerTokens: Int = 120000,
  keepTokens: Int = 40000,
  preserveTailMessages: Int = 6,
  maxPruneOutputChars: Int = 2000,
  protectTokens: Int = 40000
)

trait ConversationCompactor:
  def compactIfNeeded(history: Chunk[Msg]): ZIO[Any, Throwable, Chunk[Msg]]
  def calculateCutoffIndex(history: Chunk[Msg], config: CompactorConfig): Int

final case class EvictionConfig(
  maxResultChars: Int = 80000,
  previewHeadChars: Int = 2000,
  previewTailChars: Int = 2000,
  excludedTools: Set[String] = Set("read_file", "write_file", "edit_file", "memory_search", "memory_get", "session_search")
)

trait ToolResultEvictor:
  def evictOversizedToolResults(history: Chunk[Msg], config: EvictionConfig): ZIO[Any, Nothing, Chunk[Msg]]
```

---

## 7. Model Context Protocol Specifications (`com.weisgnosis.agents.mcp.protocol`)

```scala
package com.weisgnosis.agents.mcp.protocol

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

sealed trait RequestId
object RequestId:
  final case class StringId(value: String) extends RequestId
  final case class NumericId(value: Long) extends RequestId

  given JsonCodec[RequestId] = JsonCodec.json.transformOrFail(
    {
      case Json.Str(s) => Right(StringId(s))
      case Json.Num(n) => Right(NumericId(n.toLong))
      case other       => Left(s"Invalid request ID: $other")
    },
    {
      case StringId(s)  => Json.Str(s)
      case NumericId(n) => Json.Num(BigDecimal(n))
    }
  )

final case class JsonRpcRequest(
  jsonrpc: String = "2.0",
  id: RequestId,
  method: String,
  params: Option[Json] = None
)
object JsonRpcRequest:
  given JsonCodec[JsonRpcRequest] = DeriveJsonCodec.gen

final case class JsonRpcResponse(
  jsonrpc: String = "2.0",
  id: RequestId,
  result: Option[Json] = None,
  error: Option[JsonRpcError] = None
)
object JsonRpcResponse:
  given JsonCodec[JsonRpcResponse] = DeriveJsonCodec.gen

final case class JsonRpcNotification(
  jsonrpc: String = "2.0",
  method: String,
  params: Option[Json] = None
)
object JsonRpcNotification:
  given JsonCodec[JsonRpcNotification] = DeriveJsonCodec.gen

final case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[Json] = None
)
object JsonRpcError:
  given JsonCodec[JsonRpcError] = DeriveJsonCodec.gen

// MCP 2024-11-05 Concrete Schemas
final case class InitializeParams(
  protocolVersion: String = "2024-11-05",
  capabilities: Json = Json.Obj(),
  clientInfo: ClientInfo
)
object InitializeParams:
  given JsonCodec[InitializeParams] = DeriveJsonCodec.gen

final case class ClientInfo(name: String, version: String)
object ClientInfo:
  given JsonCodec[ClientInfo] = DeriveJsonCodec.gen

final case class InitializeResult(
  protocolVersion: String,
  capabilities: Json,
  serverInfo: ClientInfo
)
object InitializeResult:
  given JsonCodec[InitializeResult] = DeriveJsonCodec.gen

final case class McpToolDefinition(
  name: String,
  description: Option[String],
  inputSchema: Json
)
object McpToolDefinition:
  given JsonCodec[McpToolDefinition] = DeriveJsonCodec.gen

final case class ListToolsResult(tools: Chunk[McpToolDefinition])
object ListToolsResult:
  given JsonCodec[ListToolsResult] = DeriveJsonCodec.gen

final case class CallToolParams(name: String, arguments: Json)
object CallToolParams:
  given JsonCodec[CallToolParams] = DeriveJsonCodec.gen

final case class McpContent(
  `type`: String,
  text: Option[String] = None
)
object McpContent:
  given JsonCodec[McpContent] = DeriveJsonCodec.gen

final case class CallToolResult(
  content: Chunk[McpContent],
  isError: Boolean = false
)
object CallToolResult:
  given JsonCodec[CallToolResult] = DeriveJsonCodec.gen
```
