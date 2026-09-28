# Test Suite: weisgnosis.agents.core.client

Карта тестов потокового клиента LLM и SSE парсера:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `SseParserSpec.scala` | `OpenAISseParserTest.java` | Построчный парсинг чанков `data:`, игнорирование комментариев, парсинг `[DONE]` |
| `DeepSeekAdapterSpec.scala` | `DeepSeekFormatterTest.java` | Сохранение `reasoning_content` перед тулами, вырезание из старых текстовых ходов |
| `LlmClientSpec.scala` | `MockServerLlmClientTest.java` | Интеграция с мок-сервером через `zio-http`, разбор параллельных tool_calls |
