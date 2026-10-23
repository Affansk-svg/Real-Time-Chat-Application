# Real-Time Chat Application

Spring Boot 3 | WebSocket (STOMP/SockJS) | JWT | Spring Security | Hibernate/JPA | MySQL | Docker

Private (1-to-1) and group chat with live delivery, full message history stored in MySQL, online presence, typing
indicators and unread counters. Includes a ready-to-use web UI served by the same Spring Boot app.

## Architecture (modular monolith)
```
com.chat
 ├─ security   JWT creation/validation, Spring Security filter chain
 ├─ user       registration, login, user search, REST (UserController, AuthService)
 ├─ chat       rooms (private/group), members, messages, STOMP endpoints, presence
 │    ├─ Room / RoomMember / Message           JPA entities
 │    ├─ RoomService / MessageService           business rules (unit-tested with Mockito)
 │    ├─ RoomController (REST) + ChatController (STOMP)
 │    ├─ WebSocketConfig                        auth + authorisation of every STOMP frame
 │    └─ ChatNotifier / PresenceService         real-time push, online users
 └─ common     ApiException + global exception handler
```
Each package depends only "downwards" (chat -> user -> security/common), so any module can be extracted into its own service later.

## Data model (MySQL)
`users` - `chat_rooms` (type PRIVATE|GROUP, unique `private_key` per user pair) - `room_members` (role, `last_read_message_id`) - `messages` (index on `room_id, id`).
Private chats and groups share one model: a private chat is simply a room with exactly two members, so history, unread and permissions work identically.

## Real-time protocol
| Direction | Destination | Payload |
|---|---|---|
| connect | `ws://host/ws` (SockJS) with header `Authorization: Bearer <jwt>` | - |
| client -> server | `/app/rooms/{id}/send` | `{"content":"hi"}` |
| client -> server | `/app/rooms/{id}/typing` | `{}` |
| server -> room members | `/topic/rooms/{id}` | message `{id,roomId,senderId,senderName,content,createdAt}` |
| server -> room members | `/topic/rooms/{id}/typing` | `{userId,username}` |
| server -> everyone | `/topic/presence` | `{userId,online}` |
| server -> one user | `/user/queue/events` | `{type:"ROOM_ADDED",data:RoomDto}` |
| server -> sender only | `/user/queue/errors` | `{error:"..."}` |

A message is **saved first, then broadcast**, so what members see is always what is stored.
Security on the socket: CONNECT needs a valid JWT; SUBSCRIBE is allowed only to rooms the user belongs to; clients can SEND only to `/app/**` (they cannot publish straight to a topic).

## REST API (JWT in `Authorization: Bearer`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public, returns token |
| GET | `/api/users?q=` / `/api/users/me` / `/api/users/online` | search, profile, online ids |
| GET | `/api/rooms` | my rooms with last message + unread count (4 queries total) |
| POST | `/api/rooms/private/{userId}` | get-or-create private chat |
| POST | `/api/rooms/group` | `{"name":"..","memberIds":[..]}` |
| POST | `/api/rooms/{id}/members` | add member (group admin) |
| DELETE | `/api/rooms/{id}/members/me` | leave group |
| GET | `/api/rooms/{id}/messages?before=&size=` | history, keyset pagination (oldest first) |
| POST | `/api/rooms/{id}/read` | mark room as read |

## Tests
* **Unit** (JUnit 5 + Mockito, no Spring/DB): `JwtUtilTest`, `MessageServiceTest`, `RoomServiceTest`, `PresenceServiceTest`.
* **End-to-end integration** (full Spring context on a random port, H2 in MySQL mode, real HTTP and real STOMP-over-WebSocket clients):
  * `AuthIntegrationTest` - register/login/profile, duplicate user, wrong password, validation, protected endpoints.
  * `ChatFlowIntegrationTest` - private message delivered live and persisted, unread counters, group broadcast to all members,
    outsider cannot read or listen, non-member send error, oversized message rejected, socket without valid token rejected.

```powershell
mvn test
```

## Run (PowerShell)
With Docker (app + MySQL):
```powershell
docker compose up --build
```
Open http://localhost:8080, register two users (use a private window for the second), search each other with **+ Chat**, or create a **+ Group**.

Without Docker (JDK 17, Maven, MySQL on localhost:3306 with user root / password root):
```powershell
mvn spring-boot:run
```
Override settings with environment variables `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`.

## Production notes
Change `JWT_SECRET` and DB credentials; replace `ddl-auto: update` with Flyway; to run several instances swap the in-memory broker for an external STOMP broker (RabbitMQ) or Redis pub/sub, and keep presence in Redis; add rate limiting per user on `/app/**`.
