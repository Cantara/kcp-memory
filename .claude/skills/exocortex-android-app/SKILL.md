---
name: exocortex-android-app
description: kcp-sync-android — ExoCortex Android control plane. Architecture, navigation, build, key gotchas.
tags: [exocortex, android, kotlin, compose, mobile, kcp]
status: active
priority: high
relatedSkills: [exocortex-peer-setup, exocortex-serve-setup, exocortex-debug]
---

# ExoCortex Android App (kcp-sync-android)

## When to Use This

Use this skill when:
- Adding screens, features, or navigation to the Android app
- Debugging "no sessions" / "no events" problems on mobile
- Wiring new API endpoints into the Android client
- Understanding the app architecture and data flow

## Repo & Build

```
/src/cantara/kcp-sync-android/
  app/src/main/kotlin/com/cantara/kcp/exocortex/
    core/
      auth/        # ConnectionStore, ConnectionConfig, SshConfig
      model/       # Session, Node, ToolEvent, SearchResult
      network/     # KcpApiClient, WsState, WebSocketClient, SshTunnelManager
    data/          # EventRepository (singleton: wsState + events flows)
    features/
      dashboard/   # Node cards → navigate to node
      sessions/    # NodeDetailScreen, SessionDetailScreen, SessionsScreen
      search/      # FTS search
      dispatch/    # Send task to Claude Code
      capture/     # Voice/photo/note capture
      files/       # Remote file browser
      terminal/    # SSH terminal
      process/     # Service control
      settings/    # ConnectionStore config
    ui/
      navigation/  # Screen.kt, AppNavigation.kt
      theme/       # Colors, typography, CodeTextStyle
```

Build and install:
```bash
cd /src/cantara/kcp-sync-android
./gradlew installDebug
```

## Navigation Flow

```
Dashboard (bottom nav)
  └── tap Node card → NodeDetailScreen(peerId, displayName)
        ├── Sessions tab  → SessionDetailScreen(sessionId)  [chat + live tail]
        └── Activity tab  → filtered live events → SessionDetailScreen

Sessions (bottom nav)   ← global fallback: all sessions + all live events
Search (bottom nav)     ← FTS search across all nodes
Dispatch (bottom nav)   ← send task to Claude Code
Files (bottom nav)      ← remote file browser (hub-local)
Services (bottom nav)   ← systemd service control
```

Bottom bar is hidden on: `settings`, `terminal/*`, `node_detail/*`, `session_detail/*`.

## Key Architecture

### EventRepository (singleton)
```kotlin
object EventRepository {
    val events: StateFlow<List<ToolEvent>>   // live WebSocket events
    val wsState: StateFlow<WsState>          // CONNECTED / CONNECTING / RECONNECTING / DISCONNECTED
}
```
The background `ExoCortexService` owns the WebSocket lifecycle. ViewModels only observe.

### KcpApiClient
All HTTP calls go through `KcpApiClient(config)`. Always call `apiClient.shutdown()` after use.

**Critical**: `/sessions` returns a JSON envelope `{"sessions":[...], "count": N}` — not a flat array.
Parse with `JsonObject`, extract `getAsJsonArray("sessions")`:
```kotlin
val obj = gson.fromJson(json, JsonObject::class.java)
val arr = obj.getAsJsonArray("sessions") ?: return emptyList()
```

### Session model fields
```kotlin
data class Session(
    val sessionId: String,   // NOT "id"
    val startedAt: String,   // NOT "startTime"
    val firstMessage: String,
    val turnCount: Int,
    val toolCallCount: Int,
    val projectDir: String,
    val slug: String,
    val model: String,
    val gitBranch: String,
    val endedAt: String
)
```
**Note**: `projectDir` grouping is useless — all sessions start from the same dir.
Use `firstMessage` as the human-readable identifier.

### Node model fields
```kotlin
data class Node(
    val peerId: String,       // hostname-based ID
    val displayName: String,  // friendly name set via --name flag
    val address: String,
    val status: String,
    val sessionCount: Long,
    val eventCount: Long,
    val lastSeen: String
)
```
Display: `node.displayName.ifBlank { node.peerId }`

## Key Gotchas

### Sessions load before SSH tunnel is up
ViewModels fire `loadSessions()` in `init{}`, but the SSH tunnel connecting to the hub
takes a few seconds. The request fails silently and sessions never appear.

**Fix**: Observe `wsState` and reload on `CONNECTED`:
```kotlin
init {
    loadSessions()
    viewModelScope.launch {
        wsState.drop(1).collect { state ->   // drop(1) not distinctUntilChanged — StateFlow fusion
            if (state == WsState.CONNECTED) loadSessions()
        }
    }
}
```
Use `drop(1)` not `distinctUntilChanged()` — applying `distinctUntilChanged` to a
`StateFlow` is a no-op (operator fusion) and causes a deprecation warning in Kotlin.

### ViewModel with constructor parameters
Use a `ViewModelProvider.Factory` companion:
```kotlin
companion object {
    fun factory(application: Application, peerId: String) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NodeDetailViewModel(application, peerId) as T
        }
}
```
In Composable:
```kotlin
val viewModel: NodeDetailViewModel = viewModel(
    factory = NodeDetailViewModel.factory(
        LocalContext.current.applicationContext as Application, peerId
    )
)
```

### Sharing composable functions between screens
`private fun` composables can't cross files. Use `internal fun` to share within the
same Gradle module (e.g., `SessionRow`, `EventRow`, `WsStateBanner` in SessionsScreen.kt
are `internal` so NodeDetailScreen.kt can use them).

### PullToRefreshBox
Needs `@OptIn(ExperimentalMaterial3Api::class)`. Import:
```kotlin
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
```
Only one `ExperimentalMaterial3Api` import per file — don't duplicate it.

## API Endpoints Used by App

| Endpoint | Used in |
|----------|---------|
| `GET /health` | Dashboard health chip |
| `GET /nodes` | Dashboard node cards |
| `GET /sessions?limit=N` | NodeDetailScreen, SessionsScreen |
| `GET /sessions/{sessionId}/content` | SessionDetailScreen (chat history) |
| `GET /stats` | Dashboard stats row |
| `GET /search?q=...` | SearchScreen |
| `GET /events/search?q=...` | (future) |
| `GET /files?path=...` | FileBrowserScreen |
| `GET /files/content?path=...` | FileBrowserScreen |
| `POST /process` | ProcessControlScreen |
| `POST /dispatch` | DispatchScreen |
| `POST /capture` | CaptureScreen |
| `WS  /ws` | Live events + session tail |

## WebSocket Live Tail

`SessionDetailViewModel.watchLive(sessionId)` opens a second WS connection to `/ws`
(not the same one as `ExoCortexService`). Filters by `type == "session_message"` and
matching `sessionId`. Deduplicates by `uuid`.

Event format:
```json
{"type":"session_message","sessionId":"...","role":"user|assistant","text":"...","timestamp":"...","uuid":"..."}
```

## Node Detail Screen Structure

```kotlin
NodeDetailScreen(peerId, displayName, onNavigateToSessionDetail, onBack)
  TabRow: ["Sessions", "Activity"]

  Sessions tab:
    PullToRefreshBox
    LazyColumn of SessionRow (SessionsScreen.kt)
    Shows error message if API call fails (red text)

  Activity tab:
    LazyColumn of EventRow (SessionsScreen.kt)
    Filtered: events.filter { it.peerId == peerId }
```

## Current Node Topology

| Node | peerId | displayName | Role |
|------|--------|-------------|------|
| Local laptop | X1-Carbon-2022 | X1-Carbon | peer → Mimir |
| ironclaw0 | ip-172-31-27-228... | Mimir | hub, --serve 127.0.0.1:8443 |
| ironclaw1 | ip-172-31-18-208... | Klaw | peer → Mimir |

Android SSH tunnel: phone → SSH → ironclaw0:8443 (via SshTunnelManager).
WS tunnel: same SSH tunnel, proxied to ws://localhost:18443/ws.

## Related Skills

- **exocortex-peer-setup** — Configure --peer and --name for nodes
- **exocortex-serve-setup** — Configure --serve for mobile access
- **exocortex-debug** — Diagnose sync and tunnel issues

---

**Status:** active | **Priority:** high
**Tags:** #exocortex #android #kotlin #compose #mobile #kcp
**Last Updated:** 2026-04-13
