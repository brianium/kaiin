# 005: Sfere Integration

**Status:** Complete
**Priority:** High
**Dependencies:** 002-registry-metadata, 004-handler-generation

## Summary

Define how kaiin uses `::kaiin/target` to determine sfere dispatch semantics: `::sfere/broadcast` for patterns with wildcards, `::sfere/with-connection` for exact keys.

## Sfere Concepts Review

From the sfere library:

### Connection Keys
Hierarchical structure: `[scope-id [:category identifier]]`
```clojure
[::sfere/default-scope [:lobby "alice"]]
[:user-123 [:room "general"]]
```

### Wildcard Character
The keyword `:*` matches any value at that position:
```clojure
[:* [:lobby :*]]          ;; All users in any lobby
[:* [:chat "general"]]    ;; All users in the "general" chat
```

### Dispatch Effects

**`::sfere/broadcast`** - dispatch to all matching connections:
```clojure
[::sfere/broadcast {:pattern [:* [:chat :*]]} nested-effects]
```

**`::sfere/with-connection`** - dispatch to a specific connection:
```clojure
[::sfere/with-connection [scope [:category id]] nested-effects]
```

## Target to Sfere Mapping

### Rule: Wildcard Detection

```clojure
(defn has-wildcard? [target]
  (some #(= :* %) (flatten target)))
```

### Broadcast (wildcards present)

When `::kaiin/target` contains `:*`, wrap effects in `::sfere/broadcast`:

```clojure
;; Target with wildcard
::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]

;; After token replacement (room-id = "general")
[:* [:chat "general"]]

;; Generated sfere effect
[::sfere/broadcast
 {:pattern [:* [:chat "general"]]}
 [:chat/send-message "general" "Hello!"]]
```

### With-Connection (no wildcards)

When `::kaiin/target` has no wildcards, wrap in `::sfere/with-connection`:

```clojure
;; Target without wildcard
::kaiin/target [::sfere/default-scope [:user [::kaiin/signal :user-id]]]

;; After token replacement (user-id = "alice")
[::sfere/default-scope [:user "alice"]]

;; Generated sfere effect
[::sfere/with-connection
 [::sfere/default-scope [:user "alice"]]
 [:user/update-profile "alice" {:name "Alice Smith"}]]
```

## Common Target Patterns

### Broadcast to Room/Channel

All users in a specific room:
```clojure
::kaiin/target [:* [:room [::kaiin/path-param :room-id]]]
```

### Broadcast to All

Every connected client:
```clojure
::kaiin/target [:* :*]
```

### Single User by Signal

Direct message to a user identified in signals:
```clojure
::kaiin/target [::sfere/default-scope [:user [::kaiin/signal :recipient-id]]]
```

### Self (Current User)

The user who made the request (requires user ID in signals or session):
```clojure
::kaiin/target [::sfere/default-scope [:user [::kaiin/signal :user-id]]]
```

## Exclude Patterns

Sfere broadcast supports excluding connections. Should kaiin support this?

### Option A: Static Exclude in Metadata

```clojure
{::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]
 ::kaiin/target-exclude [::sfere/default-scope [:user [::kaiin/signal :sender-id]]]}
```

Generated:
```clojure
[::sfere/broadcast
 {:pattern [:* [:chat "general"]]
  :exclude #{[::sfere/default-scope [:user "alice"]]}}
 effects]
```

### Option B: No Exclude Support

Keep kaiin simple. If exclusion is needed, write a custom handler.

**Recommendation:** Start with Option B. Add exclusion if use cases demand it.

## SSE Connection Establishment

Sfere stores connections when `::sfere/key` is present in the response. Kaiin handlers return `::twk/with-open-sse? true`, meaning connections are NOT stored by default.

For connection establishment (joining a room/lobby), a separate endpoint is needed:

```clojure
;; This is NOT a kaiin-generated handler
;; It's a custom handler for establishing persistent connections
(defn join-lobby [{:keys [signals]}]
  (let [username (:username signals)]
    {::sfere/key [:lobby username]  ;; Store this connection
     ::twk/fx [[::twk/patch-elements (lobby-ui username)]]}))
```

**Important:** Kaiin generates handlers for dispatching effects to existing connections. Connection establishment is a separate concern.

## Integration Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Application                            │
├─────────────────────────────────────────────────────────────┤
│  Reitit Router                                              │
│  ├── Static routes (pages, assets)                          │
│  ├── Connection establishment routes (custom handlers)      │
│  │   └── Returns {::sfere/key [...]}                        │
│  └── Kaiin router (effect dispatch routes)                  │
│      └── Returns {::twk/fx [[::sfere/broadcast ...]]        │
│                   ::twk/with-open-sse? true}                │
├─────────────────────────────────────────────────────────────┤
│  Middleware Stack                                           │
│  ├── twk/with-datastar (SSE, signals)                       │
│  └── sfere interceptors (connection storage)                │
├─────────────────────────────────────────────────────────────┤
│  Sandestin Dispatch                                         │
│  ├── Application effects                                    │
│  ├── Twk effects (patch-elements, patch-signals)            │
│  └── Sfere effects (broadcast, with-connection)             │
└─────────────────────────────────────────────────────────────┘
```

## Open Questions

1. ~~**Exclude Support:**~~ **RESOLVED** - No exclude support for v1. Write a custom handler if needed.

2. ~~**Connection Establishment:**~~ **RESOLVED** - Application responsibility. Kaiin only generates dispatch routes.

3. ~~**Scope ID:**~~ **RESOLVED** - Scope is part of `::kaiin/target` directly. No special handling needed.

4. ~~**Store Access:**~~ **RESOLVED** - Not needed. Wildcard detection is static (checked before token replacement). Handlers don't need the store.

5. ~~**Nested Wildcards:**~~ **RESOLVED** - Support whatever sfere supports. If there's a `:*` anywhere in the target, use broadcast. Simple rule.

## Related Specs

- [002-registry-metadata](./002-registry-metadata.md) - Target syntax
- [004-handler-generation](./004-handler-generation.md) - How sfere effects are generated
