/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dotty.tools.scaladoc

import dotty.tools.dotc.core.Contexts.ContextBase
import zio.*

/** Build-only adapter for the pinned Scaladoc context/classpath defect. */
object WeisGnosisScaladocDriver extends ZIOAppDefault:
  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    getArgs.flatMap(arguments =>
      ZIO
        .fromEither(arguments.toList match
          case "-classpath" :: classpath :: remaining
              if !remaining.exists(Set("-classpath", "-cp", "--class-path")) =>
            Right(classpath -> remaining)
          case _ =>
            Left(new IllegalArgumentException("Expected one leading documentation classpath")))
        .flatMap((classpath, remaining) => generate(classpath, remaining))
    )

  private def generate(classpath: String, arguments: List[String]): Task[Unit] =
    ZIO
      .attemptBlocking:
        val (parsed, context) = Scaladoc.extract(arguments.toArray, (new ContextBase).initialCtx)
        parsed match
          case Some(args)
              if !context.reporter.hasErrors && !context.reporter.hasWarnings && args.tastyDirs.isEmpty =>
            Option(args.output) match
              case Some(output) =>
                val prepared = args.copy(classpath = classpath)
                util.IO.delete(output)
                Scaladoc.run(prepared)(using context)
                if prepared.generateInkuire then
                  Scaladoc.dumpInkuireDB(output.getAbsolutePath, prepared)
                Either.cond(
                  !context.reporter.hasErrors && !context.reporter.hasWarnings,
                  (),
                  new IllegalArgumentException("Scaladoc reported errors or warnings")
                )
              case None =>
                Left(new IllegalArgumentException("Documentation output directory is required"))
          case Some(args) if args.tastyDirs.nonEmpty =>
            Left(
              new IllegalArgumentException(
                "Supply individual TASTy files, not documentation directories"
              )
            )
          case _ => Left(new IllegalArgumentException("Invalid Scaladoc arguments"))
      .flatMap(ZIO.fromEither)
