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

import scala.quoted.*
import zio.json.ast.Json

private[tool] object ToolSchemaDerivation:
  def derive[A: Type](using Quotes): Expr[ToolSchema[A]] =
    import quotes.reflect.*

    def fail(tpe: TypeRepr, reason: String): Nothing =
      report.errorAndAbort(
        s"Cannot derive ToolSchema for ${tpe.show}: $reason. Use Tool.fromFunction with an explicit schema for custom JSON formats."
      )

    def obj(fields: List[(String, Expr[Json])]): Expr[Json] =
      val entries = fields.map((name, value) => '{ (${ Expr(name) }, $value) })
      '{ Json.Obj(${ Varargs(entries) }*) }

    def array(values: List[Expr[Json]]): Expr[Json] = '{ Json.Arr(${ Varargs(values) }*) }
    def kind(name: String): Expr[Json] = obj(List("type" -> '{ Json.Str(${ Expr(name) }) }))
    def nullable(value: Expr[Json]): Expr[Json] = obj(
      List("anyOf" -> array(List(value, kind("null"))))
    )

    def annotations(symbols: List[Symbol]): List[Term] = symbols.flatMap(_.annotations)
    def relatedSymbols(symbol: Symbol): List[Symbol] =
      val companions = List(symbol, symbol.companionClass, symbol.companionModule)
        .filter(_ != Symbol.noSymbol)
      (companions ++ companions.map(_.moduleClass)).filter(_ != Symbol.noSymbol).distinct

    def checkAnnotations(tpe: TypeRepr, values: List[Term]): Unit =
      values.foreach: annotation =>
        val name = annotation.tpe.typeSymbol.fullName
        if name.startsWith("zio.json.json") && name != "zio.json.jsonField" then
          fail(tpe, s"unsupported JSON annotation $name")

    def schema(input: TypeRepr, ancestors: List[TypeRepr]): Expr[Json] =
      val tpe = input.dealias
      if ancestors.size >= 64 then fail(tpe, "input nesting exceeds 64 levels")
      if ancestors.exists(_ =:= tpe) then fail(tpe, "recursive input types are not supported")
      val next = tpe :: ancestors
      tpe match
        case value if value =:= TypeRepr.of[String]  => kind("string")
        case value if value =:= TypeRepr.of[Boolean] => kind("boolean")
        case value
            if List(
              TypeRepr.of[Byte],
              TypeRepr.of[Short],
              TypeRepr.of[Int],
              TypeRepr.of[Long],
              TypeRepr.of[BigInt]
            ).exists(_ =:= value) =>
          kind("integer")
        case value
            if List(TypeRepr.of[Float], TypeRepr.of[Double], TypeRepr.of[BigDecimal])
              .exists(_ =:= value) =>
          kind("number")
        case AppliedType(constructor, List(element))
            if constructor.typeSymbol.fullName == "scala.Option" =>
          element.dealias match
            case AppliedType(inner, _) if inner.typeSymbol.fullName == "scala.Option" =>
              fail(tpe, "nested Option has an ambiguous JSON representation")
            case _ => nullable(schema(element, next))
        case AppliedType(constructor, List(element))
            if Set(
              "scala.collection.immutable.List",
              "scala.collection.immutable.Vector",
              "scala.collection.immutable.Seq",
              "scala.collection.immutable.Set",
              "zio.Chunk"
            ).contains(constructor.typeSymbol.fullName) =>
          obj(List("type" -> '{ Json.Str("array") }, "items" -> schema(element, next)))
        case AppliedType(constructor, List(key, value))
            if constructor.typeSymbol.fullName == "scala.collection.immutable.Map" =>
          if !(key =:= TypeRepr.of[String]) then fail(tpe, "map keys must be String")
          obj(
            List("type" -> '{ Json.Str("object") }, "additionalProperties" -> schema(value, next))
          )
        case _ =>
          val symbol = tpe.typeSymbol
          checkAnnotations(tpe, annotations(List(symbol)))
          if symbol.flags.is(Flags.Enum) then
            val cases = symbol.children
            annotations(relatedSymbols(symbol) ++ cases.flatMap(relatedSymbols)).foreach:
              annotation =>
                val name = annotation.tpe.typeSymbol.fullName
                if name.startsWith("zio.json.json") then
                  fail(tpe, s"unsupported JSON annotation $name")
            if cases.isEmpty || !cases.forall(_.isTerm) then
              fail(tpe, "only enum types whose cases are all singletons are supported")
            obj(
              List(
                "type" -> '{ Json.Str("string") },
                "enum" -> array(cases.map(value => '{ Json.Str(${ Expr(value.name) }) }))
              )
            )
          else productSchema(tpe, symbol, next)

    def productSchema(tpe: TypeRepr, symbol: Symbol, next: List[TypeRepr]): Expr[Json] =
      if !symbol.flags.is(Flags.Case) || symbol.flags.is(Flags.Module) || symbol.fullName
          .startsWith("scala.Tuple")
      then fail(tpe, "expected a supported primitive, collection or case class")
      val parameters = symbol.primaryConstructor.paramSymss.flatten
      val fields = symbol.caseFields.map: field =>
        val parameter        = parameters.find(_.name == field.name).toList
        val fieldAnnotations = annotations(field :: parameter)
        checkAnnotations(tpe, fieldAnnotations)
        val renamed = fieldAnnotations
          .filter(_.tpe.typeSymbol.fullName == "zio.json.jsonField")
          .map:
            case Apply(_, List(Literal(StringConstant(name)))) => name
            case _ => fail(tpe, "@jsonField requires a literal name")
        val name      = renamed.headOption.getOrElse(field.name)
        val fieldType = tpe.memberType(field).dealias
        val optional = fieldType match
          case AppliedType(constructor, _) => constructor.typeSymbol.fullName == "scala.Option"
          case _                           => false
        val defaulted = parameter.exists(_.flags.is(Flags.HasDefault))
        val value     = schema(fieldType, next)
        (
          name,
          if defaulted && !optional then nullable(value) else value,
          !optional && !defaulted
        )
      if fields.map(_._1).distinct.size != fields.size then fail(tpe, "duplicate JSON field names")
      obj(
        List(
          "type"       -> '{ Json.Str("object") },
          "properties" -> obj(fields.map((name, value, _) => name -> value)),
          "required" -> array(fields.collect { case (name, _, true) =>
            '{ Json.Str(${ Expr(name) }) }
          }),
          "additionalProperties" -> '{ Json.Bool(false) }
        )
      )

    val root = TypeRepr.of[A].dealias
    if !root.typeSymbol.flags.is(Flags.Case) || root.typeSymbol.flags.is(
        Flags.Module
      ) || root.typeSymbol.flags.is(Flags.Enum) || root.typeSymbol.fullName.startsWith(
        "scala.Tuple"
      )
    then fail(root, "tool input must be an object case class")
    val generated = schema(root, Nil)
    '{
      new ToolSchema[A]:
        val jsonSchema: Json = $generated
    }
