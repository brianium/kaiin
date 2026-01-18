# 002: Registry Metadata Schema

**Status:** Draft
**Priority:** High
**Dependencies:** None

## Summary

Define the `::kaiin/*` namespaced keys that extend sandestin effect registrations with HTTP routing and connection management metadata.

## Overview

Kaiin extends sandestin registries by adding metadata keys under the `ascolais.kaiin` namespace. These keys are included in sandestin's `describe` output as user-defined metadata.

## Metadata Keys

### `::kaiin/path` (required)

The HTTP path for this effect. Supports reitit path parameters.

```clojure
::kaiin/path "/chat/message"
::kaiin/path "/chat/:room-id/message"
::kaiin/path "/user/:user-id/profile"
```

**Type:** `string`

### `::kaiin/method` (optional)

The HTTP method for this route. Defaults to `:post`.

```clojure
::kaiin/method :post
::kaiin/method :get
::kaiin/method :put
::kaiin/method :delete
```

**Type:** `keyword`
**Default:** `:post`

### `::kaiin/signals` (required)

A Malli map schema describing the shape of Datastar signals this effect expects. This serves two purposes:

1. **Documentation** - LLMs and humans can inspect what state the effect uses
2. **Extraction** - The generated handler extracts these values from the request

```clojure
;; Simple flat signals
::kaiin/signals [:map [:message :string]]

;; Nested signals
::kaiin/signals [:map
                 [:user [:map
                         [:name :string]
                         [:email :string]]]
                 [:preferences [:map
                                [:theme :keyword]]]]
```

**Type:** Malli map schema (must be `:map` at the root)

### `::kaiin/dispatch` (required)

A vector matching the effect's `::sandestin/schema`, with token placeholders for dynamic values. The generated handler replaces tokens with actual values from signals and path parameters.

```clojure
;; Schema: [:tuple [:= :chat/send] :string :string]
;; Tokens reference signals
::kaiin/dispatch [:chat/send
                  [::kaiin/signal :room-id]
                  [::kaiin/signal :message]]

;; Tokens can reference nested signal paths
::kaiin/dispatch [:user/update
                  [::kaiin/signal [:user :id]]
                  [::kaiin/signal [:user :name]]]

;; Tokens can reference reitit path parameters
::kaiin/dispatch [:chat/send
                  [::kaiin/path-param :room-id]
                  [::kaiin/signal :message]]
```

**Type:** Vector with effect key and arguments (may include tokens)

### `::kaiin/target` (required)

A vector that expands to an sfere connection key. Determines whether the effect dispatches via `::sfere/broadcast` or `::sfere/with-connection`.

**Rule:** If the target contains sfere wildcard characters (`:*`), use `::sfere/broadcast`. Otherwise, use `::sfere/with-connection`.

```clojure
;; Broadcast to all users in a room (has :* wildcard)
::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]

;; Single connection (no wildcards)
::kaiin/target [::sfere/default-scope [:user [::kaiin/signal :user-id]]]

;; Broadcast to all connections (wildcards everywhere)
::kaiin/target [:* :*]
```

**Type:** Vector (sfere key structure with optional tokens)

## Token Types

### `[::kaiin/signal path]`

Extracts a value from Datastar signals.

```clojure
[::kaiin/signal :message]           ;; (:message signals)
[::kaiin/signal [:user :name]]      ;; (get-in signals [:user :name])
```

- Single keyword: direct key lookup
- Vector of keywords: `get-in` semantics

### `[::kaiin/path-param param-name]`

Extracts a value from reitit path parameters.

```clojure
[::kaiin/path-param :room-id]       ;; (:room-id path-params)
[::kaiin/path-param :user-id]
```

## Complete Example

```clojure
(def chat-registry
  {::s/effects
   {:chat/send-message
    {::s/description "Send a message to a chat room"
     ::s/schema [:tuple [:= :chat/send-message] :string :string]
     ::s/handler (fn [{:keys [dispatch]} sys room-id message]
                   ;; Handler implementation
                   {:sent true})

     ;; Kaiin metadata
     ::kaiin/path "/chat/:room-id/message"
     ::kaiin/method :post
     ::kaiin/signals [:map
                      [:message :string]
                      [:username :string]]
     ::kaiin/dispatch [:chat/send-message
                       [::kaiin/path-param :room-id]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]}}})
```

## Malli Schema for Kaiin Metadata

```clojure
(def Token
  [:or
   [:tuple [:= ::kaiin/signal] :keyword]
   [:tuple [:= ::kaiin/signal] [:vector :keyword]]
   [:tuple [:= ::kaiin/path-param] :keyword]])

(def KaiinMetadata
  [:map
   [::kaiin/path :string]
   [::kaiin/method {:optional true} :keyword]
   [::kaiin/signals [:fn #(= :map (first %))]]  ;; Must be map schema
   [::kaiin/dispatch [:vector :any]]             ;; Vector with effect key + args/tokens
   [::kaiin/target [:vector :any]]])             ;; Sfere key with optional tokens
```

## Open Questions

1. **Signal Schema Validation:** Should we validate that all `[::kaiin/signal ...]` tokens in `::kaiin/dispatch` are covered by `::kaiin/signals` schema?

2. **Path Parameter Inference:** Should we automatically infer path params from `::kaiin/path` and validate they're used correctly?

3. **Optional Signals:** How do we handle optional signal values? Should tokens support a default value?
   ```clojure
   [::kaiin/signal :page {:default 1}]
   ```

4. **Type Coercion:** Should path params be coerced based on usage context? (e.g., string "123" to int 123)

## Related Specs

- [001-core-api](./001-core-api.md) - How metadata is accessed via describe
- [003-token-replacement](./003-token-replacement.md) - How tokens are replaced at runtime
