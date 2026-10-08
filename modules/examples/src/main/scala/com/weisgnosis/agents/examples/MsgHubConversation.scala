/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{
  AgentConfig,
  ModelInputTransform,
  PeerDeliveryOutcome,
  ReActAgent
}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.coordination.MsgHub
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import zio.*

/** Offline persistent-agent collaboration; no credentials or external requests. */
object MsgHubConversation extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] = ZIO.scoped:
    val client = LlmClient.mockEffectful((history, _, _) =>
      val reply =
        if history.exists(_.text.contains("Peer message from \"Alice\"")) then
          "Bob: Alice recommends typed outcomes and scoped resources."
        else "Use typed outcomes and scoped resources."
      ZIO.succeed(TurnOutput.FinalText(Msg.assistant(reply)))
    )
    for
      alice <- ReActAgent.make(
        config = AgentConfig(name = "Alice"),
        client = client,
        modelInputTransform = ModelInputTransform.peerAware
      )
      bob <- ReActAgent.make(
        config = AgentConfig(name = "Bob"),
        client = client,
        modelInputTransform = ModelInputTransform.peerAware
      )
      _      <- MsgHub.make(Chunk(alice, bob))
      first  <- alice.run(Msg.user("Suggest an implementation approach."))
      second <- bob.run(Msg.user("Summarize Alice's recommendation."))
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Conversation delivery was rejected"))
        .unless(
          (first.peerDeliveries ++ second.peerDeliveries)
            .forall(_.outcome == PeerDeliveryOutcome.Delivered)
        )
      _ <- Console.printLine(second.reply.text)
    yield ()
