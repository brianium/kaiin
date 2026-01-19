# Specifications

This directory contains living specifications for kaiin features and concepts.

**Project Objective:** Adding HTTP interfaces to effects in Sandestin registries. Given a sandestin dispatch function, produce a reitit router for use in a web application.

## Implementation Status

Core library is complete. All specs (001-005) are implemented in `src/clj/ascolais/kaiin.clj`.

**Next step:** 007-action-handlers-for-broadcast - use sandestin actions for kaiin broadcast routes, then update demo to use kaiin-generated `/message` route.

## Public API

The main namespace `ascolais.kaiin` exports:

```clojure
;; Primary entry point
(kaiin/router dispatch)
(kaiin/router dispatch {:prefix "/api"})

;; For testing without sandestin
(kaiin/routes-from-metadata metadata-seq)

;; Metadata keys for effect registrations
::kaiin/path      ;; HTTP path, e.g., "/chat/:room-id/message"
::kaiin/method    ;; HTTP method (default :post)
::kaiin/signals   ;; Malli map schema for Datastar signals
::kaiin/dispatch  ;; Effect vector with token placeholders
::kaiin/target    ;; Sfere connection key pattern

;; Token types for dispatch/target
[::kaiin/signal :key]           ;; Extract from signals
[::kaiin/signal [:nested :key]] ;; Nested signal path
[::kaiin/path-param :param]     ;; Extract from path params
```

## Spec Index

| Spec | Status | Description |
|------|--------|-------------|
| [001-core-api](./001-core-api.md) | Complete | Primary API: `kaiin/router` function and options |
| [002-registry-metadata](./002-registry-metadata.md) | Complete | Schema for `::kaiin/*` keys in effect registrations |
| [003-token-replacement](./003-token-replacement.md) | Complete | How signal and path-param tokens are replaced |
| [004-handler-generation](./004-handler-generation.md) | Complete | Ring handler generation from effect metadata |
| [005-sfere-integration](./005-sfere-integration.md) | Complete | Target semantics for sfere broadcast/with-connection |
| [006-lobby-demo](./006-lobby-demo.md) | Complete | Port of sfere lobby demo using kaiin conventions |
| [007-action-handlers-for-broadcast](./007-action-handlers-for-broadcast.md) | Active | Use sandestin actions (not effects) for kaiin broadcast routes |

Status values: Draft, Active, Complete, Archived

## Key Design Decisions

1. **Middleware Configuration** - Kaiin generates handlers returning twk response maps (`{::twk/fx [...] ::twk/with-open-sse? true}`). Application wraps router with `twk/with-datastar` middleware.

2. **No System/Store Injection** - Kaiin handlers don't need system or sfere store. They return data; twk middleware handles dispatch.

3. **Strict Validation** - Fail fast at router creation time if tokens reference invalid signals or path params.

4. **Connection Establishment** - Application responsibility. Kaiin only generates dispatch routes. Routes returning `::sfere/key` are custom handlers.

5. **Wildcard Detection** - If `::kaiin/target` contains `:*`, use `::sfere/broadcast`. Otherwise, `::sfere/with-connection`.

## Dependencies

Hard dependencies (installed by kaiin):
- `metosin/malli` - Schema validation for metadata
- `metosin/reitit` - Router generation

Peer dependencies (installed by consuming application):
- `ascolais/sandestin` - Effect dispatch system (kaiin calls `describe` on dispatch)
- `ascolais/twk` - Datastar middleware (interprets `::twk/fx` response maps)
- `ascolais/sfere` - Connection management (interprets `::sfere/broadcast` effects)

Kaiin produces data structures with namespaced keywords (e.g., `::twk/fx`, `::sfere/broadcast`) but doesn't call functions from twk or sfere directly.

## Lobby Demo

See [006-lobby-demo](./006-lobby-demo.md) for a working example in `dev/src/clj/demo/`.

**Current status:** Demo uses custom handlers. See [007-action-handlers-for-broadcast](./007-action-handlers-for-broadcast.md) for migrating `/message` to a kaiin-generated route using sandestin actions.

The demo demonstrates:
- Correct middleware placement (at router level, not ring-handler level)
- Connection establishment patterns with sfere
- Real-time broadcasting with twk/sfere
- Composing custom routes with `kaiin/routes`
