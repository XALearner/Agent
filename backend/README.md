# Agent Backend

Spring Boot 3 + Java 17 + MyBatis-Plus + MySQL backend scaffold for the existing frontend.

## Run

1. Create the database tables with `src/main/resources/schema.sql`.
2. Update MySQL credentials in `src/main/resources/application.yml`.
3. Start the backend:

```bash
mvn spring-boot:run
```

The service listens on `http://localhost:8000`, matching `frontend/.env`.

OpenAPI UI is available at `http://localhost:8000/swagger-ui/index.html`.

## Run With Docker

Start MySQL and backend together:

```bash
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
