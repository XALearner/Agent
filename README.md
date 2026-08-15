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

## Backend Docker

```bash
cd backend
docker compose up -d --build
```

This starts both `backend` and `mysql`.
