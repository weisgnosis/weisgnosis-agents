# Low-Level Technical Specification (LLD)

> **Document:** Low-Level Technical Specification (LLD)  
> **System:** `weisgnosis-agents`  
> **Namespace:** `com.weisgnosis.agents`  
> **Platform:** Scala 3.9.0 LTS, ZIO 2.1.26+, zio-json 0.9.1+  
> **License:** Apache-2.0  
> **Status:** Approved / Living Document  

---

## 1. Domain Model Specification (`com.weisgnosis.agents.core.model`)

### 1.1. Roles & Execution States
```scala
package com.weisgnosis.agents.core.model

import zio.json.*

enum Role:
  case System
  case User
  case Assistant
  case Tool

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
  case Success
  case Error
  case Interrupted
  case Denied

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
  final case class Text(text: String) extends ContentBlock
  
  final case class Thinking(
    thought: String,
    signature: Option[String] = None
  ) extends ContentBlock
  
  final case class ToolUse(
    id: String,
    name: String,
    input: Json,
    isServerTool: Boolean = false
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

  // Tagged polymorphic codec with discriminator field "type"
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
    content.collect { case ContentBlock.Thinking(t, _) => t }.mkString("\n")

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
```

---

## 2. Event Specification: The 31 Typed Events (`com.weisgnosis.agents.core.event`)

All 31 events implement a unified sealed hierarchy with discriminator `"event"`:

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
  final case class AgentStartEvent(
    sessionId: String,
    replyId: String,
    agentId: String,
    source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class AgentEndEvent(
    replyId: String,
    source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class AgentProgressEvent(
    message: String,
    currentStep: Int,
    totalSteps: Option[Int] = None,
    source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 2. Text Deltas
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

  // 4. Tool Calls
  final case class ToolCallStartEvent(callId: String, name: String, blockIndex: Int, source: Option[String] = None) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolCallDeltaEvent(callId: String, delta: String, blockIndex: Int, source: Option[String] = None) extends AgentEvent:
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

### 3.1. Tool Traits and Types
```scala
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.error.AgentError
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
  def execute(input: In): ZIO[R, AgentError, Out]
  def decodeInput(raw: Json): Either[String, In]
  def encodeOutput(out: Out): String

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

  def executeRaw(name: String, rawArgs: Json): ZIO[Any, AgentError, String] =
    tools.get(name) match {
      case None =>
        ZIO.fail(AgentError.ToolNotFound(name))
      case Some(tool) =>
        tool.decodeInput(rawArgs) match {
          case Left(err) =>
            ZIO.fail(AgentError.ToolArgumentError(name, err))
          case Right(typedIn) =>
            tool.asInstanceOf[Tool[Any, Any, Any]].execute(typedIn).map(tool.asInstanceOf[Tool[Any, Any, Any]].encodeOutput)
        }
    }
```

---

## 4. ReAct Loop Tail Recursion Signature (`com.weisgnosis.agents.core.agent`)

```scala
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.LlmClient
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.Toolkit
import zio.*
import zio.stream.ZStream

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
  iterationCount: Int
)

trait ReActAgent:
  def run(prompt: String, initialHistory: Chunk[Msg] = Chunk.empty): ZIO[Any, AgentError, AgentResult]
  def streamEvents(prompt: String, initialHistory: Chunk[Msg] = Chunk.empty): ZStream[Any, AgentError, AgentEvent]

object ReActAgent:
  def step(
    config: AgentConfig,
    toolkit: Toolkit,
    client: LlmClient,
    history: Chunk[Msg],
    iteration: Int,
    emptyThinkingRetries: Int,
    eventHub: Hub[AgentEvent]
  ): ZIO[Any, AgentError, AgentResult] =
    if iteration > config.maxIterations then
      ZIO.fail(AgentError.MaxIterationsExceeded(config.maxIterations))
    else
      val currentTurn =
        for {
          // 1. Invoke Model Stream
          streamedChunks <- client.stream(history, toolkit.definitions, config)
          accumulatedTurn <- StreamAccumulator.consume(streamedChunks, eventHub)
          
          result <- accumulatedTurn match {
            // Case A: Pure Text Finished
            case TurnOutput.FinalText(assistantMsg) =>
              ZIO.succeed(AgentResult(assistantMsg, history :+ assistantMsg, iteration))

            // Case B: Thinking Only (Loopback Retry)
            case TurnOutput.ThinkingOnly(thinkingMsg) if emptyThinkingRetries < config.emptyThinkingRetryLimit =>
              val reminder = Msg.user("You generated internal thinking but returned no user response or tool call. Please provide your response.")
              step(config, toolkit, client, history :+ thinkingMsg :+ reminder, iteration + 1, emptyThinkingRetries + 1, eventHub)

            case TurnOutput.ThinkingOnly(_) =>
              ZIO.fail(AgentError.EmptyThinkingError("Exceeded retry limit for thinking-only turns without content."))

            // Case C: Tool Calls
            case TurnOutput.ToolCalls(assistantMsg, calls) =>
              for {
                results <- ZIO.foreachPar(calls) { call =>
                  executeWithDanglingReconcile(call, toolkit, eventHub)
                }
                toolResultMsg = Msg(role = Role.Tool, content = results)
                nextHistory = history :+ assistantMsg :+ toolResultMsg
                nextStep <- step(config, toolkit, client, nextHistory, iteration + 1, 0, eventHub)
              } yield nextStep
          }
        } yield result

      // Invariant: Dangling Tool Reconciliation on Fiber Cancellation
      currentTurn.onInterrupt {
        ZIO.succeed(println("[ReActAgent] Interrupted. Performing Dangling Tool Reconciliation."))
      }
```

---

## 5. Model Context Protocol Specifications (`com.weisgnosis.agents.mcp.protocol`)

```scala
package com.weisgnosis.agents.mcp.protocol

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
```
