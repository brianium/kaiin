# Specifications

This directory contains living specifications for kaiin features and concepts.

**Project Objective:** Adding HTTP interfaces to effects in Sandestin registries. Given a sandestin dispatch function, produce a reitit router for use in a web application.

## Current Priorities

1. **002-registry-metadata** - Foundation for all other specs
2. **003-token-replacement** - Core mechanism for dynamic dispatch
3. **004-handler-generation** - Ties everything together
4. **001-core-api** - Public API surface
5. **005-sfere-integration** - Connection dispatch semantics

All high-level design questions have been resolved. See below for decisions.

## Spec Index

| Spec | Status | Description |
|------|--------|-------------|
| [001-core-api](./001-core-api.md) | Active | Primary API: `kaiin/router` function and options |
| [002-registry-metadata](./002-registry-metadata.md) | Active | Schema for `::kaiin/*` keys in effect registrations |
| [003-token-replacement](./003-token-replacement.md) | Active | How signal and path-param tokens are replaced |
| [004-handler-generation](./004-handler-generation.md) | Active | Ring handler generation from effect metadata |
| [005-sfere-integration](./005-sfere-integration.md) | Active | Target semantics for sfere broadcast/with-connection |
| [006-lobby-demo](./006-lobby-demo.md) | Active | Port of sfere lobby demo using kaiin conventions |

Status values: Draft, Active, Complete, Archived

## High-Level Open Questions

These questions span multiple specs and need resolution before implementation:

### 1. Middleware Configuration - RESOLVED

**Decision:** Option A - Kaiin generates handlers that return twk response maps (`{::twk/fx [...] ::twk/with-open-sse? true}`). The application is responsible for wrapping the router with `twk/with-datastar` middleware. This keeps kaiin focused on route generation and avoids coupling to specific HTTP servers.

### 2. System/Store Injection - RESOLVED

**Decision:** Kaiin handlers don't need access to system or sfere store. They return twk response maps with sfere effects - the actual dispatch happens in twk middleware which constructs the system, and sfere's registry has the store closed over in its effects. Kaiin is purely about route generation and response shaping.

### 3. Actions vs Effects - RESOLVED

**Decision:** Support both. From kaiin's perspective, there's no difference - both are dispatch vectors wrapped in sfere effects. Sandestin handles action expansion internally. Kaiin inspects both `(describe dispatch :effects)` and `(describe dispatch :actions)` for `::kaiin/*` metadata.

### 4. Validation Strictness - RESOLVED

**Decision:** Strict. Fail fast at router creation time if:
- `[::kaiin/signal :foo]` references a key not extractable from `::kaiin/signals` schema
- `[::kaiin/path-param :bar]` references a param not in `::kaiin/path`

This catches configuration errors early rather than at runtime.

### 5. Connection Establishment - RESOLVED

**Decision:** Option A - Pure application responsibility. Kaiin only generates dispatch routes (which return `::twk/with-open-sse? true`). Connection establishment routes that return `::sfere/key` are a separate concern and written by the application.

## Dependencies

Hard dependencies (required at runtime):
- `ascolais/twk` - Datastar effects for sandestin
- `ascolais/sfere` - Connection management

Peer dependency (installed by consuming application):
- `ascolais/sandestin` - Effect dispatch system

Development/test dependencies:
- `ascolais/sandestin`
- `metosin/reitit`

## Lobby Demo

See [006-lobby-demo](./006-lobby-demo.md) for the complete port of the sfere lobby demo using kaiin conventions.
