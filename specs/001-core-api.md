# 001: Core API and Router Generation

**Status:** Complete
**Priority:** High
**Dependencies:** None

## Summary

Define the primary public API for kaiin: generating a reitit router from a sandestin dispatch function.

## Problem Statement

Applications using sandestin for effect dispatch, twk for Datastar SSE responses, and sfere for connection management need a way to expose effects as HTTP endpoints. Currently, developers must manually:

1. Create ring handlers that parse signals
2. Dispatch effects with correct arguments
3. Handle sfere connection routing
4. Return properly formatted twk responses

Kaiin automates this by inspecting sandestin registry metadata and generating a reitit router.

## Sandestin Introspection

Sandestin's `describe` function provides access to registry metadata:

```clojure
(require '[ascolais.sandestin.describe :as describe])

;; Get all effects with their metadata
(describe/describe dispatch :effects)

;; Returns sequence of maps like:
;; ({:ascolais.sandestin/key :chat/send-message
;;   :ascolais.sandestin/type :effect
;;   :ascolais.sandestin/description "Send a message"
;;   :ascolais.sandestin/schema [:tuple [:= :chat/send-message] :string :string]
;;   ;; User-defined metadata (kaiin keys) are included:
;;   :ascolais.kaiin/path "/chat/message"
;;   :ascolais.kaiin/method :post
;;   :ascolais.kaiin/signals [:map [:message :string]]
;;   ...})

;; Direct registry access also available:
(:registry dispatch)  ;; => {::s/effects {...}, ::s/actions {...}, ...}
```

The key insight: sandestin already includes arbitrary user-defined metadata in describe output, so kaiin can add its own namespaced keys to effect registrations.

## Proposed API

### Primary Entry Point

```clojure
(require '[ascolais.kaiin.core :as kaiin])

;; Create a router from a dispatch function
(kaiin/router dispatch)

;; Create a router with options
(kaiin/router dispatch {:middleware [...]
                        :data {...}})
```

### Router Composition

The returned router is a standard reitit router that can be composed with other routers:

```clojure
(require '[reitit.ring :as ring])

(def app
  (ring/ring-handler
    (ring/router
      [["/" {:get home-handler}]
       ["/api" (kaiin/router dispatch)]  ;; Compose kaiin router under /api
       ["/admin" admin-routes]])))
```

### Options Map

```clojure
{:data           {...}          ;; Reitit route data merged into all routes
 :prefix         nil            ;; Path prefix (default: none, paths come from metadata)
 :default-method :post}         ;; Default HTTP method (default: :post)
```

Note: Kaiin doesn't need system or sfere store - handlers return twk response maps with sfere effects. The twk middleware handles dispatch, and sfere's registry has the store closed over.

## Route Generation Process

1. Call `(describe/describe dispatch :effects)` and `(describe/describe dispatch :actions)` to get all registered items
2. Filter for items with `::kaiin/path` metadata
3. For each matching effect or action, generate a route:
   - Path from `::kaiin/path`
   - Method from `::kaiin/method` (default `:post`)
   - Handler generated per spec 004
   - Parameters from `::kaiin/signals`

## Example Registry with Kaiin Metadata

```clojure
{::s/effects
 {:chat/send-message
  {::s/description "Send a message to a chat room"
   ::s/schema [:tuple [:= :chat/send-message] :string :string]
   ::s/handler (fn [ctx sys room-id message] ...)

   ;; Kaiin metadata (see spec 002 for full schema)
   ::kaiin/path "/chat/:room-id/message"
   ::kaiin/method :post
   ::kaiin/signals [:map [:message :string]]
   ::kaiin/dispatch [:chat/send-message
                     [::kaiin/path-param :room-id]
                     [::kaiin/signal :message]]
   ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]}}}
```

## Open Questions

1. ~~**Actions vs Effects:**~~ **RESOLVED** - Support both. From kaiin's perspective they're identical - just dispatch vectors.

2. ~~**Path Parameters:**~~ **RESOLVED** - Use `[::kaiin/path-param :room-id]` token syntax, consistent with `[::kaiin/signal ...]`.

3. ~~**Validation:**~~ **RESOLVED** - Yes, strict validation at router creation time. Fail fast on misconfigured tokens.

4. ~~**Error Responses:**~~ **RESOLVED** - Return HTTP 400 with error details. Runtime token resolution failures are client errors (malformed request).

5. ~~**Route Conflicts:**~~ **RESOLVED** - Fail at router creation. Duplicate path+method combinations are configuration errors.

## Related Specs

- [002-registry-metadata](./002-registry-metadata.md) - Schema for `::kaiin/*` keys
- [003-token-replacement](./003-token-replacement.md) - Signal/path token replacement
- [004-handler-generation](./004-handler-generation.md) - Generated handler structure
- [005-sfere-integration](./005-sfere-integration.md) - Target semantics for sfere
