# weisgnosis-agents-core

Ядро мультиагентного фреймворка на Scala 3.9 LTS и ZIO 2. Реализует чистый, типобезопасный цикл рассуждения и действий (ReAct Loop), потоковые события, систему сообщений и вызов инструментов.

---

## 📦 Структура пакетов

| Пакет | Назначение | Исходный модуль AgentScope Java | Оценка SLOC (Main / Test) |
| :--- | :--- | :--- | :---: |
| [`model`](src/main/scala/weisgnosis/agents/core/model/README.md) | Сообщения (`Msg`), роли (`Role`), блоки контента (`ContentBlock`) | `agentscope-core/.../message/` | ~150 / ~200 |
| [`event`](src/main/scala/weisgnosis/agents/core/event/README.md) | 31 типизированное событие (`AgentEvent`) для SSE-стриминга | `agentscope-core/.../hook/` & `event/` | ~120 / ~180 |
| [`tool`](src/main/scala/weisgnosis/agents/core/tool/README.md) | Типобезопасные инструменты (`Tool`, `Toolkit`) на `zio-json` | `agentscope-core/.../tool/` | ~300 / ~350 |
| [`client`](src/main/scala/weisgnosis/agents/core/client/README.md) | Потоковый LLM-клиент (`zio-http`), SSE-парсер, DeepSeek адаптер | `agentscope-core/.../model/` | ~450 / ~400 |
| [`agent`](src/main/scala/weisgnosis/agents/core/agent/README.md) | Хвостовой ReAct-цикл, машина состояний, реконсилиация висячих тулов | `agentscope-core/.../agent/ReActAgent.java` | ~450 / ~650 |

---

## 🎯 Ключевые архитектурные инварианты
1. **Zero Null / Zero Mutation:** Никаких `var`, `null`, `throw` или мутабельных коллекций. Только `case class`, `Chunk`, `Option` и чистые ZIO-эффекты.
2. **Pure Codecs:** Кодеки JSON используют `zio.json` и `zio.json.ast.Json` без медленного `zio-schema`.
3. **Structured Concurrency:** Отмена родительского фибера немедленно и детерминированно прерывает вызовы LLM и тулов.
4. **LLM Fault Tolerance:** Защита от 400 Bad Request при прерываниях (Dangling Tool Reconciliation) и от пустых рассуждений (Thinking-Only Loopback).
