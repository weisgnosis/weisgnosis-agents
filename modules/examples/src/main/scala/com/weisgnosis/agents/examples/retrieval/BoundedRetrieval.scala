/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples.retrieval

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.{Tool, ToolSchema}
import java.nio.charset.StandardCharsets
import java.util.Locale
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Host-owned immutable excerpts; this recipe has no document ingestion or network backend. */
final case class SourceExcerpt(
    documentId: String,
    revisionId: String,
    chunkId: String,
    text: String
) derives JsonCodec:
  def citationId: String = s"$documentId:$revisionId:$chunkId"

final case class SearchInput(query: String, maximumResults: Int) derives JsonCodec, ToolSchema

enum RetrievalStatus derives JsonCodec:
  case Complete, Truncated

final case class RetrievedEvidence(citationId: String, source: SourceExcerpt) derives JsonCodec
final case class RetrievalBatch(evidence: Chunk[RetrievedEvidence], status: RetrievalStatus)
    derives JsonCodec
final case class EvidenceCitation(citationId: String, quote: String) derives JsonCodec, ToolSchema
final case class EvidenceAnswer(answer: String, citations: Chunk[EvidenceCitation])
    derives JsonCodec,
      ToolSchema

final case class RetrievalLimits(
    maximumCandidates: Int = 32,
    maximumQueryBytes: Int = 1024,
    maximumResults: Int = 8,
    maximumExcerptBytes: Int = 4096,
    maximumObservationBytes: Int = 32768
)

trait Retriever:
  def retrieve(input: SearchInput): ZIO[Scope, AgentError, RetrievalBatch]

object BoundedRetrieval:
  private def bytes(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length

  /** Validate before scanning; duplicate identities cannot silently replace evidence. */
  def lexical(
      corpus: Chunk[SourceExcerpt],
      limits: RetrievalLimits = RetrievalLimits()
  ): Either[AgentError, Retriever] =
    val positive = List(
      limits.maximumCandidates,
      limits.maximumQueryBytes,
      limits.maximumResults,
      limits.maximumExcerptBytes,
      limits.maximumObservationBytes
    ).forall(_ > 0)
    val validIdentity = corpus.forall(source =>
      List(source.documentId, source.revisionId, source.chunkId)
        .forall(_.matches("[A-Za-z0-9_-]{1,64}"))
    )
    if !positive then Left(AgentError.InvalidConfiguration("Retrieval limits must be positive"))
    else if corpus.size > limits.maximumCandidates then
      Left(AgentError.InvalidConfiguration("Retrieval corpus exceeds candidate limit"))
    else if !validIdentity || corpus.map(_.citationId).distinct.size != corpus.size then
      Left(AgentError.InvalidConfiguration("Retrieval source identities must be valid and unique"))
    else if corpus.exists(source => !JsonBoundary.validUnicode(Json.Str(source.text))) then
      Left(AgentError.InvalidConfiguration("Retrieval excerpt contains malformed Unicode"))
    else if corpus.exists(source => bytes(source.text) > limits.maximumExcerptBytes) then
      Left(AgentError.InvalidConfiguration("Retrieval excerpt exceeds UTF-8 byte limit"))
    else
      Right(
        new Retriever:
          def retrieve(input: SearchInput): ZIO[Scope, AgentError, RetrievalBatch] =
            ZIO.suspendSucceed:
              if !JsonBoundary.validUnicode(Json.Str(input.query)) || bytes(
                  input.query
                ) > limits.maximumQueryBytes || input.query.trim.isEmpty
              then
                ZIO.fail(
                  AgentError.InvalidMessage(
                    "Retrieval query is empty, malformed Unicode or exceeds UTF-8 byte limit"
                  )
                )
              else if input.maximumResults <= 0 || input.maximumResults > limits.maximumResults then
                ZIO.fail(AgentError.InvalidMessage("Retrieval result count exceeds host limits"))
              else
                val terms = input.query.toLowerCase(Locale.ROOT).split("\\s+").toList.distinct
                val ranked = corpus
                  .map(source =>
                    (source, terms.count(source.text.toLowerCase(Locale.ROOT).contains))
                  )
                  .filter(_._2 > 0)
                  .sortBy((source, score) => (-score, source.citationId))
                val evidence = ranked
                  .take(input.maximumResults)
                  .map((source, _) => RetrievedEvidence(source.citationId, source))
                val status =
                  if ranked.size > evidence.size then RetrievalStatus.Truncated
                  else RetrievalStatus.Complete
                val batch = RetrievalBatch(evidence, status)
                if bytes(batch.toJson) > limits.maximumObservationBytes then
                  ZIO.fail(
                    AgentError.InvalidMessage("Retrieval observation exceeds UTF-8 byte limit")
                  )
                else ZIO.succeed(batch)
      )

  /** Only the latest successful search may support the displayed answer. Failures clear evidence. */
  def tool(
      retriever: Retriever,
      manifest: Ref[Chunk[RetrievedEvidence]]
  ): Tool[Any, SearchInput, RetrievalBatch] =
    Tool.derived[SearchInput, RetrievalBatch](
      "search_documents",
      "Search the host's fixed document excerpts. Cite exact returned citation IDs and quotes.",
      isReadOnly = true,
      isConcurrencySafe = false
    )(input =>
      manifest.set(Chunk.empty) *> ZIO
        .scoped(retriever.retrieve(input))
        .tap(batch => manifest.set(batch.evidence))
    )

  /** Attribution check before display; it does not prove that a quote supports a claim. */
  def validateAnswer(
      answer: EvidenceAnswer,
      manifest: Chunk[RetrievedEvidence]
  ): Either[AgentError, EvidenceAnswer] =
    val known = manifest.map(evidence => evidence.citationId -> evidence.source.text).toMap
    val valid =
      JsonBoundary.validUnicode(Json.Str(answer.answer)) && answer.citations.forall(citation =>
        citation.quote.nonEmpty && JsonBoundary.validUnicode(Json.Str(citation.quote)) &&
          JsonBoundary.validUnicode(Json.Str(citation.citationId)) &&
          known.get(citation.citationId).exists(_.contains(citation.quote))
      )
    if !valid || (manifest.nonEmpty && answer.citations.isEmpty) then
      Left(AgentError.DeserializationError("Answer cites unavailable evidence or an inexact quote"))
    else Right(answer)
