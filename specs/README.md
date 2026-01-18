# Specifications

This directory contains living specifications for kaiin features and concepts.

**Project Objective:** Adding HTTP interfaces to effects in Sandestin registries. Given a sandestin dispatch function, produce a reitit router for use in a web application.

## Current Priorities

1. **Resolve Open Questions** - Several design decisions need clarification before implementation
2. **002-registry-metadata** - Foundation for all other specs
3. **003-token-replacement** - Core mechanism for dynamic dispatch
4. **004-handler-generation** - Ties everything together
5. **001-core-api** - Public API surface
6. **005-sfere-integration** - Connection dispatch semantics

## Spec Index

| Spec | Status | Description |
|------|--------|-------------|
| [001-core-api](./001-core-api.md) | Draft | Primary API: `kaiin/router` function and options |
| [002-registry-metadata](./002-registry-metadata.md) | Draft | Schema for `::kaiin/*` keys in effect registrations |
| [003-token-replacement](./003-token-replacement.md) | Draft | How signal and path-param tokens are replaced |
| [004-handler-generation](./004-handler-generation.md) | Draft | Ring handler generation from effect metadata |
| [005-sfere-integration](./005-sfere-integration.md) | Draft | Target semantics for sfere broadcast/with-connection |

Status values: Draft, Active, Complete, Archived

## High-Level Open Questions

These questions span multiple specs and need resolution before implementation:

### 1. Middleware Configuration

**Question:** Should kaiin automatically include twk middleware, or require the application to configure it externally?

**Context:** Twk's `with-datastar` middleware requires an `->sse-response` adapter (http-kit, ring, etc.) that kaiin can't know about.

**Options:**
- A) Kaiin generates handlers that return twk response maps; application wraps with middleware
- B) Kaiin accepts SSE adapter in options and configures middleware internally

### 2. System/Store Injection

**Question:** How do sandestin `system` and sfere `store` get injected into handlers?

**Context:** Effect dispatch needs a system map. Sfere needs its store. Both must be available at request time.

**Options:**
- A) Pass in `kaiin/router` options, stored in handler closures
- B) Middleware adds to request map
- C) Use request itself as system (matches twk pattern)

### 3. Actions vs Effects

**Question:** Should kaiin support both effects AND actions, or only effects?

**Context:** Actions are pure functions that expand to effects. They could also have HTTP interfaces.

**Recommendation:** Start with effects only. Actions can be added if use cases emerge.

### 4. Validation Strictness

**Question:** How strict should token/schema validation be at router creation time?

**Options:**
- A) Strict: Validate all tokens match schemas/paths, fail fast on misconfiguration
- B) Lenient: Only fail at runtime when tokens can't resolve
- C) Configurable

### 5. Connection Establishment

**Question:** Does kaiin provide any support for connection establishment routes (the `::sfere/key` response pattern)?

**Context:** Kaiin handlers return `::twk/with-open-sse? true` (non-persistent). Persistent connections need separate setup.

**Options:**
- A) Pure application responsibility
- B) Kaiin provides optional helper for connection routes
- C) Additional metadata key `::kaiin/persistent?` changes behavior

## Dependencies

Hard dependencies (required at runtime):
- `ascolais/twk` - Datastar effects for sandestin
- `ascolais/sfere` - Connection management

Peer dependency (installed by consuming application):
- `ascolais/sandestin` - Effect dispatch system

Development/test dependencies:
- `ascolais/sandestin`
- `metosin/reitit`

## Lobby Demo Reference

The sfere project contains a lobby demo that kaiin will replicate using kaiin conventions. Key patterns to support:

1. **Join lobby** - Establish persistent connection with `::sfere/key`
2. **Send message** - Broadcast to all lobby participants
3. **Leave lobby** - Close connection, notify others

The demo shows the separation between:
- Connection establishment (custom handler, returns `::sfere/key`)
- Effect dispatch (kaiin-generated handlers, use stored connections)
