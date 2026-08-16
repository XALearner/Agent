# Agent Backend

Spring Boot 3 + Java 17 + MyBatis-Plus + MySQL backend scaffold for the existing frontend.

## Run

1. Create the database tables with `src/main/resources/schema.sql`.
2. Update MySQL credentials in `src/main/resources/application.yml`.
3. Set your Qwen API key:

```bash
$env:DASHSCOPE_API_KEY="your_dashscope_api_key"
```

4. Start the backend:

```bash
mvn spring-boot:run
```

The service listens on `http://localhost:8000`, matching `frontend/.env`.

OpenAPI UI is available at `http://localhost:8000/swagger-ui/index.html`.

## Model Configuration

The chat API uses a provider abstraction backed by LangChain4j. Qwen is the current default provider and is called through LangChain4j's OpenAI-compatible streaming chat model.

Configure the default provider and model with environment variables:

```bash
LLM_DEFAULT_PROVIDER=qwen
QWEN_MODEL=qwen-plus
```

The frontend can also pass optional fields in `/chat_on_docs` requests:

```json
{
  "message": "你好",
  "provider": "qwen",
  "model": "qwen-plus"
}
```

To add another model provider later, implement `com.agent.llm.ChatModelClient` with a LangChain4j `ChatModel` or `StreamingChatModel`.

## Run With Docker

Start MySQL and backend together:

```bash
copy .env.example .env
# Edit .env and set DASHSCOPE_API_KEY
docker compose up -d --build
```

Stop services:

```bash
docker compose down
```

Remove services and database/upload volumes:

```bash
docker compose down -v
```
