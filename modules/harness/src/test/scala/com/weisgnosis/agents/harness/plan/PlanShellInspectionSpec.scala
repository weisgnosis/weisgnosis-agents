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

import zio.*
import zio.test.*
import zio.json.ast.Json

object PlanShellInspectionSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanShellInspectionSpec")(
    test("authoritative shell envelope requires exactly one string command field") {
      val invalid = Chunk[Json](
        Json.Str("pwd"),
        Json.Arr(),
        Json.Obj(),
        Json.Obj("command" -> Json.Num(1)),
        Json.Obj("other"   -> Json.Str("pwd")),
        Json.Obj("command" -> Json.Str("pwd"), "command" -> Json.Str("rm file")),
        Json.Obj("command" -> Json.Str("pwd"), "cwd"     -> Json.Str("elsewhere")),
        Json.Obj("command" -> Json.Str("pwd"), "env"     -> Json.Obj())
      )
      assertTrue(
        invalid.forall(input =>
          PlanShellInspection.inspectInput(input) == Left(ShellRejection.InvalidInput)
        ),
        PlanShellInspection
          .inspectInput(Json.Obj("command" -> Json.Str("head -n 2 file")))
          .exists(_.argv == Chunk("head", "-n", "2", "file"))
      )
    },
    test("explicit command forms produce exact argv without executing a process") {
      val commands = List(
        "pwd",
        "ls",
        "ls src",
        "ls -lah",
        "ls -alh src/main",
        "cat README.md docs/README.md",
        "head file.txt",
        "head -n 20 file.txt",
        "tail file.txt",
        "tail -n 10000 file.txt",
        "wc -l file.txt"
      )
      assertTrue(
        commands.forall(command =>
          PlanShellInspection.inspect(command).exists(_.argv.toList == command.split(" ").toList)
        )
      )
    },
    test(
      "redirection pipelines sequencing substitutions quoting escaping globs and control characters are rejected"
    ) {
      val syntax = List(
        ">",
        ">>",
        "<",
        "|",
        "||",
        "&",
        "&&",
        ";",
        "$HOME",
        "$(pwd)",
        "`pwd`",
        "\"x\"",
        "'x'",
        "\\x",
        "*",
        "?",
        "[a]",
        "{a,b}",
        "(pwd)",
        "!",
        "#comment",
        "~",
        "=",
        "\n",
        "\r",
        "\t",
        "\u0000",
        "\u001b"
      )
      assertTrue(
        syntax.forall(fragment =>
          List(s"cat $fragment", s"cat file $fragment pwd", s"$fragment pwd")
            .forall(command =>
              PlanShellInspection.inspect(command) == Left(ShellRejection.UnsupportedSyntax)
            )
        )
      )
    },
    test(
      "unknown binaries wrappers shell interpreters path-qualified programs and option-rich programs are denied"
    ) {
      val commands = List(
        "rm file",
        "mv a b",
        "sed -i file",
        "git status",
        "git diff --output file",
        "find .",
        "env pwd",
        "sudo cat file",
        "command pwd",
        "busybox cat file",
        "sh -c pwd",
        "bash -c pwd",
        "python script.py",
        "pwsh script.ps1",
        "powershell script.ps1",
        "cmd /c dir",
        "/bin/cat file",
        "./pwd",
        "PWD",
        "curl host"
      )
      assertTrue(
        commands.forall(command =>
          PlanShellInspection.inspect(command) == Left(ShellRejection.UnsupportedCommand)
        )
      )
    },
    test("only explicitly supported argument shapes are admitted") {
      val commands = List(
        "pwd -L",
        "pwd extra",
        "ls --help",
        "ls --color",
        "ls -R",
        "ls -la src extra",
        "ls a b",
        "cat",
        "cat -",
        "cat --",
        "cat -n file",
        "head",
        "head -c 10 file",
        "tail -f file",
        "wc file",
        "wc -c file",
        "wc -l a b"
      )
      assertTrue(
        commands.forall(command =>
          PlanShellInspection.inspect(command) == Left(ShellRejection.UnsupportedArguments)
        )
      )
    },
    test("option-like paths and implicit standard input cannot slip through argument positions") {
      val commands = List(
        "ls -",
        "head -",
        "tail -",
        "head -n 2 -",
        "tail -n 2 --follow",
        "wc -l --files0-from",
        "cat file -n"
      )
      assertTrue(
        commands.forall(command =>
          PlanShellInspection.inspect(command) == Left(ShellRejection.UnsupportedArguments)
        ),
        PlanShellInspection.inspect("cat ./-n").exists(_.arguments == Chunk("./-n"))
      )
    },
    test("line counts are positive bounded ASCII decimals without overflow") {
      val invalid = List("0", "-1", "10001", "99999999999999999999", "1.5", "1e3")
      assertTrue(
        invalid.forall(count =>
          PlanShellInspection.inspect(s"head -n $count file") == Left(
            ShellRejection.UnsupportedArguments
          )
        ),
        PlanShellInspection.inspect("head -n 1 file").isRight,
        PlanShellInspection.inspect("tail -n 10000 file").isRight
      )
    },
    test("input size and unsupported Unicode are rejected before token interpretation") {
      assertTrue(
        PlanShellInspection.inspect("cat " + "a" * 4092).isRight,
        PlanShellInspection.inspect("cat " + "a" * 4093) == Left(ShellRejection.TooLong),
        List("cat café", "cat file\u00a0name", "cat file\u202ename", "cat \uff0dflag")
          .forall(command =>
            PlanShellInspection.inspect(command) == Left(ShellRejection.UnsupportedSyntax)
          )
      )
    },
    test("ASCII spacing normalizes into argv and blank commands have a distinct rejection") {
      assertTrue(
        PlanShellInspection
          .inspect("  head   -n  2   docs/file.txt  ")
          .exists(_.argv == Chunk("head", "-n", "2", "docs/file.txt")),
        List("", " ", "    ").forall(command =>
          PlanShellInspection.inspect(command) == Left(ShellRejection.Empty)
        )
      )
    }
  )
