# Test Suite: weisgnosis.agents.harness.memory

Карта тестов компактинга памяти и оценки токенов:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `ConversationCompactorSpec.scala` | `ConversationCompactorTest.java` | Сжатие истории, соблюдение атомарности пар тулов, объединение старых саммари |
| `TokenCounterSpec.scala` | `TokenCounterTest.java` | Оценка токенов текста, JSON и кода |
