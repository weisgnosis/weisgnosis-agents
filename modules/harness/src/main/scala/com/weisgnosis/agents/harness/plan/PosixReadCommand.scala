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
package com.weisgnosis.agents.harness.plan

import zio.Chunk
import zio.json.JsonCodec

enum PosixReadProgram derives JsonCodec:
  case Pwd, Ls, Cat, Head, Tail, Wc

  def name: String = this match
    case Pwd  => "pwd"
    case Ls   => "ls"
    case Cat  => "cat"
    case Head => "head"
    case Tail => "tail"
    case Wc   => "wc"

/** Static command shape only; executable binding, path authority and runtime isolation belong to the host. */
final class PosixReadCommand private[plan] (
    val program: PosixReadProgram,
    val arguments: Chunk[String]
):
  def argv: Chunk[String] = Chunk(program.name) ++ arguments

enum ShellRejection derives JsonCodec:
  case InvalidInput, Empty, TooLong, UnsupportedSyntax, UnsupportedCommand, UnsupportedArguments
