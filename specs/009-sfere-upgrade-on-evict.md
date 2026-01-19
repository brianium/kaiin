# 009: Sfere Upgrade with Caffeine Store

**Status:** Complete
**Priority:** High
**Dependencies:** 006-lobby-demo, 007-action-handlers-for-broadcast

## Summary

Upgrade sfere to v0.5.0 and switch the demo from atom store to caffeine store with TTL-based expiration. The demo demonstrates explicit leave functionality; automatic "user left" on disconnect is an application-level concern beyond this demo's scope.

## Background

### Original Goal

The original goal was to have sfere's `on-evict` callback broadcast "user left" messages when connections expire. However, investigation revealed fundamental limitations:

1. **Sliding expiry syncs together**: Broadcasts touch all connections (via cache lookups), resetting all sliding windows together. When activity stops, all connections expire at the same time.

2. **SSE close detection is passive**: The server only learns a client disconnected when it tries to write to that connection. Without activity, dead connections just expire via TTL.

### Resolution

Sfere v0.5.0 now properly purges connections on SSE close (when detected). However, the decision was made that:

- **Sfere is a connection storage library**, not a connection monitoring library
- Lifecycle broadcasts ("user left") are an **application concern**
- Applications needing real-time presence detection should implement heartbeats

See sfere specs 010-011 for full discussion.

## Implementation

### deps.edn

```clojure
io.github.brianium/sfere {:git/tag "v0.5.0" :git/sha "847219a"}
```

### demo/app.clj

Simplified caffeine store with logging-only on-evict:

```clojure
(defn- on-evict
  "Log connection evictions. Sfere handles cleanup; lifecycle broadcasts
   (like 'user left') are an application concern beyond this demo's scope."
  [[_scope [_category username] :as key] _conn cause]
  (tap> {:on-evict true :key key :username username :cause cause}))

(defn- create-store
  "Create caffeine store with TTL and eviction callback."
  []
  (sfere/store {:type :caffeine
                :duration-ms 30000
                :expiry-mode :sliding
                :scheduler true
                :on-evict on-evict}))
```

### Demo Behavior

- **Explicit leave**: Click "Leave Lobby" - others see departure immediately via `:lobby/leave` action
- **Tab close**: Connection is purged from store (sfere v0.5.0 fix), on-evict logs the event
- **No automatic "user left" broadcast**: This would require application-level heartbeats

## What Was Learned

1. **TCP/SSE limitation**: Servers can't passively detect client disconnects; they must attempt a write
2. **Sliding expiry behavior**: Cache access (including reads during broadcast) resets sliding windows for all touched connections
3. **Separation of concerns**: Connection storage (sfere) vs. presence detection (application) are distinct responsibilities

## For Applications Needing Real-Time Presence

Applications that need "user left" on disconnect should implement heartbeats:

```clojure
;; Application-level heartbeat (not sfere's responsibility)
(defn heartbeat! [store dispatch]
  (doseq [key (sfere/list-keys store [:* [:lobby :*]])]
    (dispatch {} {}
      [[::sfere/with-connection key
        [::twk/patch-elements [:span.heartbeat]]]])))

;; Run periodically - failed writes flush dead connections
;; which triggers on-evict where the app can broadcast "user left"
```

## Related Specs

- [006-lobby-demo](./006-lobby-demo.md) - Original demo spec
- sfere/010-sse-close-immediate-purge - SSE close fix
- sfere/011-demo-presence - Presence detection discussion
