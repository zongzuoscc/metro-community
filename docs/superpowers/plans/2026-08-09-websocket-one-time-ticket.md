# WebSocket One-Time Ticket Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JWT-in-URI WebSocket authentication with a Redis-backed, short-lived, one-time ticket.

**Architecture:** A protected HTTP endpoint issues opaque random credentials. A Spring-created Jakarta WebSocket endpoint atomically consumes each credential from Redis before registering a connection, while a dedicated registry performs compare-and-remove session cleanup.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security, Spring Data Redis, Jakarta WebSocket, JUnit 5, MockMvc, Testcontainers Redis.

## Global Constraints

- Keep `/im/{credential}` only as a path shape; never accept JWT semantics.
- Default ticket TTL is 30 seconds and configurable with `WEBSOCKET_TICKET_TTL`.
- Redis failures are fail-closed for issuance and connection establishment.
- Never log tickets, JWTs, or request URIs.
- Do not modify the frontend in this backend commit.

---

### Task 1: Ticket configuration and Redis lifecycle

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/config/WebSocketProperties.java`
- Create: `src/main/java/cumt/zongzuo/community/websocket/WebSocketTicketService.java`
- Create: `src/main/java/cumt/zongzuo/community/websocket/WebSocketTicketStoreException.java`
- Modify: `src/main/java/cumt/zongzuo/community/CommunityApplication.java`
- Test: `src/test/java/cumt/zongzuo/community/websocket/WebSocketTicketServiceIntegrationTest.java`
- Test: `src/test/java/cumt/zongzuo/community/config/WebSocketPropertiesTest.java`

**Interfaces:**
- Produces: `IssuedWebSocketTicket issue(Long userId)` and `Long consume(String ticket)`.
- Produces: `WebSocketProperties.ticketTtl()`.

- [ ] Write tests that bind a 30-second default TTL, issue a 256-bit Base64URL ticket, consume it once, reject format errors and expiry, and fail closed against unavailable Redis.
- [ ] Run the focused tests and confirm they fail because the types do not exist.
- [ ] Implement configuration, `SET NX` issuance, and Lua `GET`/`DEL` consumption.
- [ ] Re-run the focused tests and confirm they pass.

### Task 2: Protected issuance endpoint

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/controller/WebSocketTicketController.java`
- Create: `src/main/java/cumt/zongzuo/community/dto/WebSocketTicketResponse.java`
- Test: `src/test/java/cumt/zongzuo/community/websocket/WebSocketTicketEndpointIntegrationTest.java`

**Interfaces:**
- Consumes: `WebSocketTicketService.issue(Long)`.
- Produces: `POST /api/ws/ticket -> Result<WebSocketTicketResponse>`.

- [ ] Write MockMvc tests for unauthenticated HTTP 401, authenticated issuance, and a generic HTTP 503 when Redis is unavailable.
- [ ] Run the tests and confirm the endpoint-not-found/security failures.
- [ ] Implement the controller and map store failures to `ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)`.
- [ ] Re-run the tests and confirm they pass.

### Task 3: Ticket-only WebSocket endpoint and race-safe registry

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/websocket/WebSocketSessionRegistry.java`
- Modify: `src/main/java/cumt/zongzuo/community/websocket/WebSocketServer.java`
- Modify: `src/main/java/cumt/zongzuo/community/config/WebSocketConfig.java`
- Test: `src/test/java/cumt/zongzuo/community/websocket/WebSocketConnectionIntegrationTest.java`
- Test: `src/test/java/cumt/zongzuo/community/websocket/WebSocketSessionRegistryTest.java`

**Interfaces:**
- Consumes: `Long WebSocketTicketService.consume(String)`.
- Produces: programmatic `/im/{ticket}` endpoint using a new Spring-created endpoint instance per connection.
- Produces: `Session replace(Long, Session)`, `boolean remove(Long, Session)`, and `Session find(Long)`.

- [ ] Write real WebSocket tests for one successful connection and 1008 closure for replay, expiry, forgery, original JWT, and Redis failure.
- [ ] Write a registry test showing old-session close cannot delete the replacement.
- [ ] Run the focused tests and confirm the old JWT endpoint or missing components cause the expected failures.
- [ ] Implement programmatic endpoint registration, atomic ticket consumption, secret-safe logging, and conditional session removal.
- [ ] Re-run the focused tests and confirm they pass.

### Task 4: Configuration, documentation, and verification

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-example.yml`
- Modify: `.env.example`
- Modify: `README.md`

- [ ] Document `WEBSOCKET_TICKET_TTL=PT30S`, the authenticated issuance request, one-time connection flow, and Redis fail-closed behavior.
- [ ] Run all focused WebSocket/security tests under Java 21.
- [ ] Run `./mvnw test` under Java 21 and inspect the complete result.
- [ ] Run `git diff --check` and inspect the final diff for credential logging or JWT parsing.
- [ ] Commit only the backend changes without pushing or stopping live services.
