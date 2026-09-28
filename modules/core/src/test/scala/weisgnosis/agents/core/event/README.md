# Test Suite: weisgnosis.agents.core.event

Карта тестов типизированных событий агентного цикла:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `AgentEventSerdeSpec.scala` | `AgentEventTest.java` | 100% round-trip сериализация всех 31 событий с полем `type` |
| `AgentEventStreamSpec.scala` | `AgentSpawnToolCancelEndEventTest.java` | Гарантированное закрытие стрима и эмит `AgentEndEvent` при прерывании фибера |
| `RemoteEventCodecSpec.scala` | `RemoteEventCodecTest.java` | Корректный парсинг сырых SSE строк в типизированные события |
