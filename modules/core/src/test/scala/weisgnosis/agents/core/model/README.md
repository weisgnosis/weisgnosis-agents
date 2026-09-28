# Test Suite: weisgnosis.agents.core.model

Карта тестов моделей сообщений, блоков контента и ролей:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `MsgSpec.scala` | `MsgTest.java` | Фабричные методы, добавление блоков контента, извлечение чистого текста |
| `MsgSerdeSpec.scala` | `MsgJacksonModuleTest.java` | Round-trip равенство JSON (`msg.toJson.fromJson[Msg] == Right(msg)`) |
| `ContentBlockSerdeSpec.scala` | `ContentBlockTest.java` | Сериализация каждого подтипа `ContentBlock` с дискриминатором `type` |
