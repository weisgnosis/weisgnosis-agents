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
package com.weisgnosis.agents.core.model

import zio.json.*
import zio.json.ast.Json

/** Optional wire fields may be absent, but supplied malformed values must never be discarded. */
private[model] object JsonFields:
  /** Preserve numeric leaves without the dependency Map decoder's AST-to-text conversion. */
  def metadata(obj: Json.Obj): Either[String, Map[String, Json]] =
    obj.get("metadata") match
      case None | Some(Json.Null) => Right(Map.empty)
      case Some(Json.Obj(fields)) => Right(fields.toMap)
      case Some(_)                => Left("Invalid 'metadata' field: expected an object")

  def optional[A: JsonDecoder](obj: Json.Obj, name: String): Either[String, Option[A]] =
    obj.get(name) match
      case None | Some(Json.Null) => Right(None)
      case Some(value) =>
        value.as[A].left.map(error => s"Invalid '$name' field: $error").map(Some(_))
