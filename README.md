# Agent

## Project Structure

- `frontend`: React + Vite frontend.
- `backend`: Spring Boot + Maven + MyBatis-Plus + MySQL backend.

## Backend Quick Start

1. Create MySQL tables with `backend/src/main/resources/schema.sql`.
2. Update database credentials in `backend/src/main/resources/application.yml`.
3. Start backend:

```bash
cd backend
mvn spring-boot:run
```

Backend runs on `http://localhost:8000`, matching `frontend/.env`.

## MCP Tools

The backend can expose remote MCP (Model Context Protocol) HTTP tools to Qwen function calling. It is disabled by default.

```yaml
mcp:
  enabled: true
  servers:
    demo:
      url: http://localhost:3001/mcp
      bearer-token: ${DEMO_MCP_TOKEN:}
      headers:
        X-Client: agent-backend
```

MCP tools are registered as Qwen functions named `mcp__<server>__<tool>`, for example `mcp__demo__search`.

## Backend Docker

```bash
cd backend
docker compose up -d --build
```

This starts both `backend` and `mysql`.
