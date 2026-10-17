/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.tool

import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.agent.{AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}

object ToolSchemaSpec extends ZIOSpecDefault:
  final case class ReleaseQuery(release: String) derives JsonCodec, ToolSchema
  final case class Nested(@jsonField("build_id") buildId: Int) derives JsonCodec
  type Maybe = Option[String]
  final case class AliasQuery(note: Maybe) derives JsonCodec, ToolSchema

  final case class Query(
      nested: Nested,
      labels: List[String],
      note: Option[String],
      limit: Int = 3
  ) derives JsonCodec,
        ToolSchema
  final case class Collections(values: Vector[Option[Int]], metadata: Map[String, Boolean])
      derives JsonCodec,
        ToolSchema
  final case class Box[A](value: A) derives JsonCodec
  final case class Generic(box: Box[Box[String]]) derives JsonCodec, ToolSchema

  enum Direction derives JsonCodec:
    case North, South
    override def toString: String = "Display direction"
  final case class DirectionQuery(direction: Direction) derives JsonCodec, ToolSchema
  final case class Directions(
      optional: Option[Direction],
      values: List[Direction],
      byName: Map[String, Direction],
      nested: Box[Direction]
  ) derives JsonCodec,
        ToolSchema

  final case class ScalarCollections(
      flag: Boolean,
      byte: Byte,
      short: Short,
      int: Int,
      long: Long,
      big: BigInt,
      decimal: BigDecimal,
      float: Float,
      double: Double,
      seq: Seq[String],
      set: Set[Int],
      chunk: Chunk[Boolean]
  ) derives JsonCodec,
        ToolSchema

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolSchemaSpec")(
    test("singleton enum labels agree with the codec and execute through the public agent loop"):
      ZIO
        .foreach(List(Direction.North -> "North", Direction.South -> "South")):
          (direction, label) =>
            for
              calls <- Ref.make(List.empty[Direction])
              input = DirectionQuery(direction)
              json <- ZIO.fromEither(input.toJson.fromJson[Json])
              tool = Tool.derived[DirectionQuery, String]("direction", "Read direction")(query =>
                calls.update(_ :+ query.direction).as(query.direction.toString)
              )
              toolkit = Toolkit.single(tool)
              admitted <- toolkit.executeSafe("direction", json)
              client <- LlmClient.mock(
                Chunk(
                  TurnOutput.ToolCalls(
                    Msg.assistant(Chunk(ContentBlock.ToolUse("enum-call", "direction", json)))
                  ),
                  TurnOutput.FinalText(Msg.assistant("Done"))
                )
              )
              agent    <- ReActAgent.builder().model(client).toolkit(toolkit).build
              result   <- agent.run(Msg.user("Read the direction"))
              history  <- agent.history
              executed <- calls.get
            yield assertTrue(
              json == Json.Obj("direction" -> Json.Str(label)),
              tool.decodeInput(json).contains(input),
              admitted.state == ToolResultState.Success,
              result.state == AgentRunState.Completed,
              executed == List(direction, direction),
              history.exists(_.content.exists {
                case result: ContentBlock.ToolResult =>
                  result.id == "enum-call" && result.state == ToolResultState.Success
                case _ => false
              })
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("singleton enum declarations advertise a closed object with string labels"):
      val expected =
        """{"type":"object","properties":{"direction":{"type":"string","enum":["North","South"]}},"required":["direction"],"additionalProperties":false}"""
          .fromJson[Json]
      assertTrue(expected.contains(summon[ToolSchema[DirectionQuery]].jsonSchema))
    ,
    test(
      "enum admission rejects unknown labels, wrong shapes and extra properties before execution"
    ):
      for
        calls <- Ref.make(0)
        tool = Tool.derived[DirectionQuery, String]("direction", "Read direction")(query =>
          calls.update(_ + 1).as(query.direction.toString)
        )
        results <- ZIO.foreach(
          List(
            Json.Obj("direction" -> Json.Str("East")),
            Json.Obj("direction" -> Json.Str("north")),
            Json.Obj("direction" -> Json.Null),
            Json.Obj("direction" -> Json.Num(1)),
            Json.Obj("direction" -> Json.Obj("North" -> Json.Obj())),
            Json.Obj("direction" -> Json.Str("North"), "extra" -> Json.Bool(true))
          )
        )(Toolkit.single(tool).executeSafe("direction", _))
        executed <- calls.get
      yield assertTrue(executed == 0, results.forall(_.state == ToolResultState.Error))
    ,
    test("enum schemas retain labels in optional, collection, map and nested product positions"):
      val input = Directions(
        Some(Direction.North),
        List(Direction.South, Direction.North),
        Map("home" -> Direction.South),
        Box(Direction.North)
      )
      val tool = Tool.derived[Directions, Int]("directions", "Read directions")(query =>
        ZIO.succeed(query.values.size)
      )
      for
        json     <- ZIO.fromEither(input.toJson.fromJson[Json])
        accepted <- Toolkit.single(tool).executeSafe("directions", json)
        optional <- ZIO.foreach(
          List(
            """{"values":[],"byName":{},"nested":{"value":"North"}}""",
            """{"optional":null,"values":[],"byName":{},"nested":{"value":"South"}}"""
          )
        )(raw =>
          ZIO
            .fromEither(raw.fromJson[Json])
            .flatMap(Toolkit.single(tool).executeSafe("directions", _))
        )
        rejected <- ZIO.foreach(
          List(
            """{"values":["East"],"byName":{},"nested":{"value":"North"}}""",
            """{"values":[],"byName":{"home":"East"},"nested":{"value":"North"}}""",
            """{"optional":"East","values":[],"byName":{},"nested":{"value":"North"}}""",
            """{"values":[],"byName":{},"nested":{"value":"East"}}"""
          )
        )(raw =>
          ZIO
            .fromEither(raw.fromJson[Json])
            .flatMap(Toolkit.single(tool).executeSafe("directions", _))
        )
      yield assertTrue(
        accepted.state == ToolResultState.Success,
        tool.decodeInput(json).contains(input),
        optional.forall(_.state == ToolResultState.Success),
        rejected.forall(_.state == ToolResultState.Error)
      )
    ,
    test("derived input reaches the public agent loop and produces a complete tool observation"):
      for
        calls <- Ref.make(0)
        tool = Tool.derived[ReleaseQuery, String]("read_evidence", "Read evidence")(query =>
          calls.update(_ + 1).as(query.release)
        )
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  ContentBlock
                    .ToolUse("call-1", "read_evidence", Json.Obj("release" -> Json.Str("RC2")))
                )
              )
            ),
            TurnOutput.FinalText(Msg.assistant("Reviewed RC2"))
          )
        )
        agent   <- ReActAgent.builder().model(client).toolkit(Toolkit.single(tool)).build
        result  <- agent.run(Msg.user("Review RC2"))
        history <- agent.history
        count   <- calls.get
      yield assertTrue(
        result.state == AgentRunState.Completed,
        result.reply.text == "Reviewed RC2",
        count == 1,
        history.exists(_.content.exists {
          case result: ContentBlock.ToolResult => result.id == "call-1"
          case _                               => false
        })
      )
    ,
    test("case-class derivation emits the advertised object contract"):
      val expected =
        """{"type":"object","properties":{"release":{"type":"string"}},"required":["release"],"additionalProperties":false}"""
          .fromJson[Json]
      assertTrue(expected.contains(summon[ToolSchema[ReleaseQuery]].jsonSchema))
    ,
    test("derived tool validates arguments before invoking its suspended handler"):
      for
        calls <- Ref.make(0)
        tool = Tool.derived[ReleaseQuery, String]("evidence", "Read evidence", isReadOnly = true)(
          query => calls.update(_ + 1).as(query.release)
        )
        toolkit = Toolkit.single(tool)
        before <- calls.get
        results <- ZIO.foreach(
          List(
            Json.Obj("release" -> Json.Str("RC2")),
            Json.Obj(),
            Json.Obj("release" -> Json.Num(3)),
            Json.Obj("release" -> Json.Str("RC2"), "extra" -> Json.Bool(true))
          )
        )(toolkit.executeSafe("evidence", _))
        after <- calls.get
      yield assertTrue(
        before == 0,
        after == 1,
        results.map(_.state) == List(
          ToolResultState.Success,
          ToolResultState.Error,
          ToolResultState.Error,
          ToolResultState.Error
        ),
        tool.isReadOnly
      )
    ,
    test("renamed nested fields, optional values and defaults agree with derived decoding"):
      val tool = Tool.derived[Query, Int]("query", "Query")(query => ZIO.succeed(query.limit))
      val inputs = List(
        """{"nested":{"build_id":1},"labels":[]}"""                          -> true,
        """{"nested":{"build_id":1},"labels":[],"note":null,"limit":null}""" -> true,
        """{"nested":{"build_id":1},"labels":["a"],"note":"ok","limit":5}""" -> true,
        """{"nested":{"buildId":1},"labels":[]}"""                           -> false,
        """{"nested":{"build_id":1}}"""                                      -> false,
        """{"nested":{"build_id":1},"labels":null}"""                        -> false,
        """{"nested":{"build_id":1},"labels":[],"note":1}"""                 -> false
      )
      ZIO
        .foreach(inputs): (raw, accepted) =>
          for
            json   <- ZIO.fromEither(raw.fromJson[Json])
            result <- Toolkit.single(tool).executeSafe("query", json)
          yield assertTrue(
            (result.state == ToolResultState.Success) == accepted,
            tool.decodeInput(json).isRight == accepted
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("collection elements, string-key maps and generic nested products are validated"):
      val tool = Tool.derived[Collections, Int]("collections", "Collections")(query =>
        ZIO.succeed(query.values.size)
      )
      val generic =
        Tool.derived[Generic, String]("generic", "Generic")(query =>
          ZIO.succeed(query.box.value.value)
        )
      for
        accepted <- Toolkit
          .single(tool)
          .executeSafe(
            "collections",
            Json.Obj(
              "values"   -> Json.Arr(Json.Num(1), Json.Null),
              "metadata" -> Json.Obj("ok" -> Json.Bool(true))
            )
          )
        rejected <- Toolkit
          .single(tool)
          .executeSafe(
            "collections",
            Json.Obj("values" -> Json.Arr(Json.Str("bad")), "metadata" -> Json.Obj())
          )
        nested <- Toolkit
          .single(generic)
          .executeSafe(
            "generic",
            Json.Obj("box" -> Json.Obj("value" -> Json.Obj("value" -> Json.Str("ok"))))
          )
      yield assertTrue(
        accepted.state == ToolResultState.Success,
        rejected.state == ToolResultState.Error,
        nested.state == ToolResultState.Success
      )
    ,
    test("type aliases retain optional-field semantics"):
      val tool = Tool.derived[AliasQuery, String]("alias", "Alias")(query =>
        ZIO.succeed(query.note.getOrElse("empty"))
      )
      Toolkit
        .single(tool)
        .executeSafe("alias", Json.Obj())
        .map(result => assertTrue(result.state == ToolResultState.Success))
    ,
    test("custom codec configuration uses the explicit-schema escape hatch"):
      given JsonCodecConfiguration = JsonCodecConfiguration(fieldNameMapping = SnakeCase)
      final case class CustomInput(releaseName: String) derives JsonCodec
      val schema = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj("release_name" -> Json.Obj("type" -> Json.Str("string"))),
        "required"   -> Json.Arr(Json.Str("release_name")),
        "additionalProperties" -> Json.Bool(false)
      )
      val tool = Tool.fromFunction[CustomInput, String]("custom", "Custom format", schema)(query =>
        ZIO.succeed(query.releaseName)
      )
      for
        accepted <- Toolkit
          .single(tool)
          .executeSafe("custom", Json.Obj("release_name" -> Json.Str("RC2")))
        rejected <- Toolkit
          .single(tool)
          .executeSafe("custom", Json.Obj("releaseName" -> Json.Str("RC2")))
      yield assertTrue(
        accepted.state == ToolResultState.Success,
        rejected.state == ToolResultState.Error
      )
    ,
    test("advertised scalar and collection types accept codec-produced arguments"):
      val input = ScalarCollections(
        true,
        1.toByte,
        2.toShort,
        3,
        4L,
        BigInt("12345678901234567890"),
        BigDecimal("1.25"),
        1.5f,
        2.5,
        Seq("a"),
        Set(1),
        Chunk(true)
      )
      val tool =
        Tool.derived[ScalarCollections, Boolean]("scalars", "Scalars")(_ => ZIO.succeed(true))
      for
        json   <- ZIO.fromEither(input.toJson.fromJson[Json])
        result <- Toolkit.single(tool).executeSafe("scalars", json)
      yield assertTrue(
        result.state == ToolResultState.Success,
        tool.decodeInput(json).contains(input)
      )
    ,
    test("derived factories preserve tool failure observations and interruption"):
      val failing = Tool.derived[ReleaseQuery, String]("failure", "Failure")(_ =>
        ZIO.fail(new IllegalArgumentException("unavailable"))
      )
      val interrupted =
        Tool.derived[ReleaseQuery, String]("interrupt", "Interrupt")(_ => ZIO.interrupt)
      for
        result <- Toolkit
          .single(failing)
          .executeSafe("failure", Json.Obj("release" -> Json.Str("RC2")))
        exit <- Toolkit
          .single(interrupted)
          .executeSafe("interrupt", Json.Obj("release" -> Json.Str("RC2")))
          .exit
      yield assertTrue(result.state == ToolResultState.Error, exit.isInterrupted)
    ,
    test("unsupported roots, recursive products and ambiguous wire formats fail at compile time"):
      val root = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; ToolSchema.derived[String]"""
      )
      val expanding = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; case class Node[A](next: Option[Node[List[A]]]); ToolSchema.derived[Node[Int]]"""
      )
      val sum = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; sealed trait Choice; case object Yes extends Choice; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val recursive = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; case class Node(next: Option[Node]); ToolSchema.derived[Node]"""
      )
      val aliasedNested = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; type Maybe = Option[String]; case class Query(value: Option[Maybe]); ToolSchema.derived[Query]"""
      )
      val nestedOption = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; case class Query(value: Option[Option[String]]); ToolSchema.derived[Query]"""
      )
      val excluded = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; import zio.json.*; case class Query(@jsonExclude value: String); ToolSchema.derived[Query]"""
      )
      val renamedDuplicate = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; import zio.json.*; case class Query(@jsonField("x") first: String, @jsonField("x") second: String); ToolSchema.derived[Query]"""
      )
      val map = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; case class Query(value: Map[Int, String]); ToolSchema.derived[Query]"""
      )
      assertTrue(
        root.exists(_.message.contains("object case class")),
        recursive.exists(_.message.contains("recursive input")),
        expanding.exists(_.message.contains("nesting exceeds")),
        sum.exists(_.message.contains("supported primitive")),
        nestedOption.exists(_.message.contains("nested Option")),
        aliasedNested.exists(_.message.contains("nested Option")),
        excluded.exists(_.message.contains("unsupported JSON annotation")),
        renamedDuplicate.exists(_.message.contains("duplicate JSON field names")),
        map.exists(_.message.contains("map keys must be String"))
      )
    ,
    test("root enums, payload cases and enum wire annotations remain compile-time errors"):
      val root = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; enum Choice { case Yes, No }; ToolSchema.derived[Choice]"""
      )
      val mixed = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; enum Choice { case Yes; case Value(value: String) }; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val empty = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; enum Choice { case Yes; case Empty() }; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val parent = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; import zio.json.*; @jsonDiscriminator("kind") enum Choice { case Yes, No }; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val child = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; import zio.json.*; enum Choice { @jsonHint("affirmative") case Yes; case No }; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val companion = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; import zio.json.*; enum Choice { case Yes, No }; @jsonDiscriminator("kind") object Choice; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val hintNames = typeCheckErrors(
        """import com.weisgnosis.agents.core.tool.ToolSchema; import zio.json.*; @jsonHintNames(SnakeCase) enum Choice { case Yes, No }; case class Query(value: Choice); ToolSchema.derived[Query]"""
      )
      val results = List(root, mixed, empty, parent, child, companion, hintNames)
      val expected = List(
        "object case class",
        "all singletons",
        "all singletons",
        "unsupported JSON annotation",
        "unsupported JSON annotation",
        "unsupported JSON annotation",
        "unsupported JSON annotation"
      )
      assertTrue(results.zip(expected).forall { case (errors, message) =>
        errors.exists(_.message.contains(message))
      })
  )
