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

import com.weisgnosis.agents.core.error.AgentError
import zio.Chunk
import zio.json.JsonCodec

enum ToolGroupActivation derives JsonCodec:
  case Active, Inactive

/** Meta replacement leaves External groups under separate host control; no live meta-tool is installed. */
enum ToolGroupScope derives JsonCodec:
  case Meta, External

/** Untrusted decoded declarations become registry entries only through checked register. */
final case class ToolGroup(
    name: String,
    description: String,
    activation: ToolGroupActivation = ToolGroupActivation.Active,
    scope: ToolGroupScope = ToolGroupScope.Meta,
    tools: Set[String] = Set.empty
) derives JsonCodec

/** Immutable host-selected configuration; selecting a toolkit neither owns nor revokes its tools. */
final class ToolGroups private (entries: Map[String, ToolGroup]):
  def groups: Chunk[ToolGroup] = Chunk.fromIterable(entries.toList.sortBy(_._1).map(_._2))
  def group(name: String): Option[ToolGroup] = entries.get(name)
  def activeNames: Set[String] = entries.collect {
    case (name, value) if value.activation == ToolGroupActivation.Active => name
  }.toSet
  def memberships(tool: String): Set[String] = entries.collect {
    case (name, value) if value.tools.contains(tool) => name
  }.toSet

  /** Host-facing notes include active groups from both scopes, ordered by name. */
  def activeNotes: String =
    val active = groups.filter(_.activation == ToolGroupActivation.Active)
    if active.isEmpty then "No tool groups are currently activated."
    else "Activated tool groups:\n" + describe(active, "")

  /** Model-facing notes describe this immutable snapshot and exclude External groups. */
  def metaNotes: String =
    val (active, inactive) = groups
      .filter(_.scope == ToolGroupScope.Meta)
      .partition(_.activation == ToolGroupActivation.Active)
    "Activated tool groups:\n" + describe(active, "No tool groups are currently activated.") +
      "Inactive tool groups:\n" + describe(inactive, "No tool groups are currently inactive.")

  private def describe(values: Chunk[ToolGroup], empty: String): String =
    if values.isEmpty then empty + "\n"
    else values.map(group => s"- ${group.name}: ${group.description}\n").mkString

  def register(value: ToolGroup): Either[AgentError.InvalidConfiguration, ToolGroups] =
    for
      _ <- validName(value.name)
      _ <- value.tools.toList.sorted.foldLeft[Either[AgentError.InvalidConfiguration, Unit]](
        Right(())
      )((checked, name) => checked.flatMap(_ => validName(name)))
      _ <- Either.cond(
        !entries.contains(value.name),
        (),
        invalid(s"Tool group '${value.name}' already exists")
      )
    yield new ToolGroups(entries.updated(value.name, value))

  def updateActivation(
      names: Set[String],
      activation: ToolGroupActivation
  ): Either[AgentError.InvalidConfiguration, ToolGroups] =
    checkNames(names).map(_ =>
      new ToolGroups(entries.map { case (name, value) =>
        name -> (if names.contains(name) then value.copy(activation = activation) else value)
      })
    )

  def replaceActive(names: Set[String]): Either[AgentError.InvalidConfiguration, ToolGroups] =
    checkNames(names).map(_ => replace(names, entries.keySet))

  def replaceMetaActive(names: Set[String]): Either[AgentError.InvalidConfiguration, ToolGroups] =
    for
      _ <- checkNames(names)
      _ <- Either.cond(
        names.forall(name => entries.get(name).exists(_.scope == ToolGroupScope.Meta)),
        (),
        invalid("Meta activation cannot select External tool groups")
      )
    yield replace(
      names,
      entries.collect { case (name, value) if value.scope == ToolGroupScope.Meta => name }.toSet
    )

  def addTool(group: String, tool: String): Either[AgentError.InvalidConfiguration, ToolGroups] =
    changeMembership(group, tool, _ + tool)

  /** Removing an absent membership is idempotent; the target group must exist. */
  def removeTool(
      group: String,
      tool: String
  ): Either[AgentError.InvalidConfiguration, ToolGroups.Removal] =
    changeMembership(group, tool, _ - tool).map(updated =>
      ToolGroups.Removal(
        updated,
        Set(tool).filter(name => memberships(name).nonEmpty && updated.memberships(name).isEmpty)
      )
    )

  /** Last-membership loss makes tools ungrouped and active; the host owns any optional toolkit pruning. */
  def remove(names: Set[String]): ToolGroups.Removal =
    val remaining = entries -- names
    val removedTools =
      entries.iterator.filter((name, _) => names.contains(name)).flatMap(_._2.tools).toSet
    val retainedTools = remaining.valuesIterator.flatMap(_.tools).toSet
    ToolGroups.Removal(new ToolGroups(remaining), removedTools -- retainedTools)

  def isActiveTool(name: String): Boolean =
    val containing = entries.valuesIterator.filter(_.tools.contains(name)).toList
    containing.isEmpty || containing.exists(_.activation == ToolGroupActivation.Active)

  /** Filters exact registered values, preserving attached argument policies and host scheduling overrides. */
  def select(toolkit: Toolkit): Toolkit =
    val grouped = entries.valuesIterator.flatMap(_.tools).toSet
    val active = entries.valuesIterator
      .filter(_.activation == ToolGroupActivation.Active)
      .flatMap(_.tools)
      .toSet
    Toolkit(toolkit.tools.filter((name, _) => !grouped.contains(name) || active.contains(name)))

  private def replace(names: Set[String], affected: Set[String]): ToolGroups =
    val updated = entries.map: (name, value) =>
      val activation =
        if names.contains(name) then ToolGroupActivation.Active else ToolGroupActivation.Inactive
      val group = if affected.contains(name) then value.copy(activation = activation) else value
      name -> group
    new ToolGroups(updated)

  private def changeMembership(
      group: String,
      tool: String,
      change: Set[String] => Set[String]
  ): Either[AgentError.InvalidConfiguration, ToolGroups] =
    for
      _ <- checkNames(Set(group))
      _ <- validName(tool)
    yield new ToolGroups(
      entries.updatedWith(group)(_.map(value => value.copy(tools = change(value.tools))))
    )

  private def checkNames(names: Set[String]): Either[AgentError.InvalidConfiguration, Unit] =
    names.toList.sorted.find(name => !entries.contains(name)) match
      case Some(name) => Left(invalid(s"Unknown tool group '$name'"))
      case None       => Right(())

  private def validName(name: String): Either[AgentError.InvalidConfiguration, Unit] =
    Either.cond(name.trim.nonEmpty, (), invalid("Tool group and member names must not be blank"))

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(message)

object ToolGroups:
  val empty: ToolGroups = new ToolGroups(Map.empty)

  final case class Removal(groups: ToolGroups, newlyUngrouped: Set[String])
