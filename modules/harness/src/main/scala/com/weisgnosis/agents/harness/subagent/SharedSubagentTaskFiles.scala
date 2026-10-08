/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.storage.StorageAddress
import zio.*

/**
 * Borrowed arbitrary-byte persistence. Missing is distinct from an access or storage failure.
 * Implementations own their resources and must preserve interruption and finish owned cleanup.
 */
trait TaskFileBackend:
  def read(address: StorageAddress): IO[AgentError, Option[Chunk[Byte]]]
  def write(address: StorageAddress, bytes: Chunk[Byte]): IO[AgentError, Unit]

/**
 * Explicit shared route, with no local fallback, JSON conversion or backend ownership.
 * The host supplies an authenticated owner partition; an address is not authentication.
 * This capability does not register tasks, execute them, or confer terminal-store CAS guarantees.
 */
final class SharedSubagentTaskFiles private (
    backend: TaskFileBackend,
    owner: StorageAddress,
    agent: LocalSubagentTaskWorkspace.Name,
    maxBytes: Int
):
  def read(path: String): IO[AgentError, Option[Chunk[Byte]]] =
    for
      address <- ZIO.fromEither(route(path))
      bytes   <- ZIO.suspendSucceed(backend.read(address))
      checked <- ZIO.foreach(bytes)(value => checkSize(value).as(value))
    yield checked

  def write(path: String, bytes: Chunk[Byte]): IO[AgentError, Unit] =
    for
      address <- ZIO.fromEither(route(path))
      _       <- checkSize(bytes)
      _       <- ZIO.suspendSucceed(backend.write(address, bytes))
    yield ()

  private def checkSize(bytes: Chunk[Byte]): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.StorageError("Shared task file exceeds the configured byte limit"))
      .when(bytes.length > maxBytes)
      .unit

  private def route(path: String): Either[AgentError, StorageAddress] =
    Chunk.fromArray(path.split("/", -1)) match
      case Chunk("agents", selectedAgent, "tasks", filename)
          if selectedAgent == agent.value && filename.endsWith(".json") =>
        for
          session <- LocalSubagentTaskWorkspace.Name.from(filename.stripSuffix(".json"))
          address <- StorageAddress.from(
            owner.namespace ++ Chunk(owner.key, agent.value, session.value),
            "tasks"
          )
        yield address
      case _ =>
        Left(
          AgentError.InvalidConfiguration(
            "Expected agents/<bound-agent>/tasks/<checked-session>.json; no fallback route is available"
          )
        )

object SharedSubagentTaskFiles:
  /**
   * Pure binding. Namespace capacity is checked again for each complete session address.
   * The byte limit bounds accepted values, not backend allocation or network transfer.
   */
  def bind(
      backend: TaskFileBackend,
      owner: StorageAddress,
      agent: LocalSubagentTaskWorkspace.Name,
      maxBytes: Int = 1024 * 1024
  ): Either[AgentError.InvalidConfiguration, SharedSubagentTaskFiles] =
    Either.cond(
      maxBytes > 0,
      new SharedSubagentTaskFiles(backend, owner, agent, maxBytes),
      AgentError.InvalidConfiguration("Shared task file byte limit must be positive")
    )
