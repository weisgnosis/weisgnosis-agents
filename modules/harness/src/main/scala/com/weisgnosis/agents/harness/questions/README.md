# User questions

`AskUserTool.make()` declares an external, read-only question tool. Register its
`binding` with `Toolkit.single` or `Toolkit.register`. Its checked decoder rejects
invalid batches before external handoff; existing authorization can still deny it.
Questions collect preferences and never grant permission to execute other tools.

Construct a batch with `AskUserParams.make` and ordinary `UserQuestion` and
`QuestionOption` values. The version-one JSON contract requires 1–4 questions,
2–4 choices per question, unique question IDs and texts, and unique choice IDs and
labels within each question. IDs use ASCII letters, digits, `_` and `-`, begin with
a letter, and contain at most 64 characters. Headers have at most 12 characters.
Question text, labels and descriptions have limits of 2,000, 200 and 2,000
characters. Context, previews and free-text answers have limits of 8,000
characters. Multiple-choice questions cannot have previews. Question batches and
result payloads must each fit in 65,536 UTF-8 bytes and contain valid Unicode.
Checked decoding uses the canonical version-one fields and rejects unknown fields.

Persist the suspended `AgentResult` before presentation. Project one admitted
occurrence with `pending(checkpoint, ordinal, QuestionHostBinding(...))` and present
the returned questions through the application's channel. The host correlation key
includes session, checkpoint, model reply/request, original call ordinal and the
exact checked question batch. Repeated model call IDs remain separate occurrences.

Call `answer` against the **current** checkpoint, then pass its `SuppliedToolResult`
to the existing `agent.resume`. Each question in that occurrence needs one answer:
a nonempty selection of distinct known choice IDs, or nonblank free text. Single
selection permits exactly one choice. Different pending occurrences can be answered
separately. Readable result text and `weisgnosis_user_answers` metadata describe the
same checked answers. No replacement question-generating model request is needed.

Correlation data does not authenticate users or claim an execution. The host owns
authenticated delivery, atomic response/expiry claims, persistence, duplicate
delivery prevention and unavailable-channel handling. Generic supplied results
remain a trusted host escape hatch and can bypass this helper. No UI, live waiting
channel, permission confirmation or automatic durable execution is installed.

Run the offline restore-and-answer example:

```sh
sbt "examples/runMain com.weisgnosis.agents.examples.OfflineUserQuestions"
```
