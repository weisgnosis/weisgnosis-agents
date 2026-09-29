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
import zio.json.ast.Json

/** Conservative POSIX token grammar, not a shell parser or an execution authorization. */
object PlanShellInspection:
  val MaximumCommandCharacters: Int = 4096
  val MaximumLineCount: Int         = 10000

  /** The executor and permission gate must consume the same exact authoritative envelope. */
  def inspectInput(input: Json): Either[ShellRejection, PosixReadCommand] = input match
    case Json.Obj(fields) =>
      fields.toList match
        case ("command", Json.Str(command)) :: Nil => inspect(command)
        case _                                     => Left(ShellRejection.InvalidInput)
    case _ => Left(ShellRejection.InvalidInput)

  def inspect(command: String): Either[ShellRejection, PosixReadCommand] =
    if command.length > MaximumCommandCharacters then Left(ShellRejection.TooLong)
    else if command.forall(_ == ' ') then Left(ShellRejection.Empty)
    else if !command.forall(character => tokenCharacter(character) || character == ' ') then
      Left(ShellRejection.UnsupportedSyntax)
    else
      val tokens = command.split(" +").toList.filter(_.nonEmpty)
      tokens match
        case "pwd" :: Nil => accepted(PosixReadProgram.Pwd, Nil)
        case "pwd" :: _   => Left(ShellRejection.UnsupportedArguments)
        case "ls" :: arguments =>
          arguments match
            case Nil                                 => accepted(PosixReadProgram.Ls, Nil)
            case path :: Nil if operand(path)        => accepted(PosixReadProgram.Ls, arguments)
            case flags :: Nil if listingFlags(flags) => accepted(PosixReadProgram.Ls, arguments)
            case flags :: path :: Nil if listingFlags(flags) && operand(path) =>
              accepted(PosixReadProgram.Ls, arguments)
            case _ => Left(ShellRejection.UnsupportedArguments)
        case "cat" :: arguments =>
          if arguments.nonEmpty && arguments.forall(operand) then
            accepted(PosixReadProgram.Cat, arguments)
          else Left(ShellRejection.UnsupportedArguments)
        case "head" :: arguments => lines(PosixReadProgram.Head, arguments)
        case "tail" :: arguments => lines(PosixReadProgram.Tail, arguments)
        case "wc" :: (arguments @ ("-l" :: path :: Nil)) if operand(path) =>
          accepted(PosixReadProgram.Wc, arguments)
        case "wc" :: _ => Left(ShellRejection.UnsupportedArguments)
        case _         => Left(ShellRejection.UnsupportedCommand)

  private def lines(
      program: PosixReadProgram,
      arguments: List[String]
  ): Either[ShellRejection, PosixReadCommand] =
    arguments match
      case path :: Nil if operand(path) => accepted(program, arguments)
      case "-n" :: count :: path :: Nil if lineCount(count) && operand(path) =>
        accepted(program, arguments)
      case _ => Left(ShellRejection.UnsupportedArguments)

  private def tokenCharacter(character: Char): Boolean =
    (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
      (character >= '0' && character <= '9') || "_./-".contains(character)

  private def operand(value: String): Boolean = value.nonEmpty && !value.startsWith("-")

  private def listingFlags(value: String): Boolean =
    value.startsWith("-") && value.length > 1 && value.length <= 5 && value
      .drop(1)
      .forall("alhd".contains(_))

  private def lineCount(value: String): Boolean =
    value.nonEmpty && value.length <= 5 && value.forall(character =>
      character >= '0' && character <= '9'
    ) &&
      value.toIntOption.exists(count => count > 0 && count <= MaximumLineCount)

  private def accepted(
      program: PosixReadProgram,
      arguments: List[String]
  ): Either[ShellRejection, PosixReadCommand] =
    Right(new PosixReadCommand(program, Chunk.fromIterable(arguments)))
