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
package com.weisgnosis.agents.harness.storage

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object VersionedJsonStoreSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("VersionedJsonStoreSpec")(
    test("checked addresses preserve exact namespace identity and enforce Unicode byte bounds") {
      val accepted = List(
        Chunk("a", "bc")        -> "key",
        Chunk("ab", "c")        -> "key",
        Chunk("a", "bc")        -> "KEY",
        Chunk("n" * 512)        -> ("k" * 512),
        Chunk("\u00e9" * 256)   -> ("\u00e9" * 256),
        Chunk(" \uD83D\uDE00 ") -> " exact key "
      )
      val rejected = List(
        Chunk.empty[String]         -> "key",
        Chunk(" ")                  -> "key",
        Chunk("a", "")              -> "key",
        Chunk("a\u001fb")           -> "key",
        Chunk("a")                  -> "k\u001fey",
        Chunk("a\u0000")            -> "key",
        Chunk("a")                  -> "key\u0000",
        Chunk("a")                  -> "\t",
        Chunk("n" * 513)            -> "key",
        Chunk("\u00e9" * 257)       -> "key",
        Chunk("a")                  -> ("\u00e9" * 257),
        Chunk("a" * 256, "b" * 256) -> "key",
        Chunk("\uD83D")             -> "key",
        Chunk("a")                  -> "\uDE00"
      )
      val addresses = accepted.map((namespace, key) => StorageAddress.from(namespace, key))
      assertTrue(
        addresses.forall(
          _.exists(address => address.toJson.fromJson[StorageAddress] == Right(address))
        ),
        addresses
          .collect { case Right(address) => (address.serializedNamespace, address.key) }
          .distinct
          .size == accepted.size,
        rejected.forall((namespace, key) => StorageAddress.from(namespace, key).isLeft),
        """{"namespace":["a"],"key":""}""".fromJson[StorageAddress].isLeft
      )
    },
    test(
      "revision codecs enforce positive integer tokens and retain conditional publication data"
    ) {
      val accepted = List(1L, Long.MaxValue)
      val rejected = List("0", "-1", "1.5", "9223372036854775808", "\"1\"", "null", "true")
      assertTrue(
        List(0L, -1L, Long.MinValue).forall(StorageRevision.fromLong(_).isLeft),
        rejected.forall(_.fromJson[StorageRevision].isLeft),
        accepted.forall(value =>
          StorageRevision
            .fromLong(value)
            .exists: revision =>
              val stored   = VersionedJson(Json.Arr(Json.Null, Json.Str("exact")), revision)
              val expected = ExpectedRevision.AtRevision(revision)
              val outcome  = ConditionalWrite.Applied(revision)
              revision.value == value && revision.toJson
                .fromJson[StorageRevision] == Right(revision) &&
              stored.toJson.fromJson[VersionedJson] == Right(stored) &&
              expected.toJson.fromJson[ExpectedRevision] == Right(expected) &&
              outcome.toJson.fromJson[ConditionalWrite] == Right(outcome)
        )
      )
    }
  )
