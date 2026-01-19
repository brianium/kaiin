# ascolais/kaiin

HTTP interfaces for [sandestin](https://github.com/brianium/sandestin) effects.

Kaiin generates [reitit](https://github.com/metosin/reitit) routes from metadata on sandestin effect registrations. Instead of writing HTTP handlers that extract parameters and dispatch effects, you declare the mapping and kaiin generates the handlers.

## The Problem

When building real-time applications with sandestin, you often write repetitive handler code:

```clojure
;; Without kaiin - manual handler for each effect
(defn send-message-handler [{:keys [signals path-params]}]
  (let [room-id (:room-id path-params)
        username (:username signals)
        message (:message signals)]
    {::twk/fx [[::sfere/broadcast {:pattern [:* [:room room-id :*]]}
                [:room/send-message room-id username message]]]
     ::twk/with-open-sse? true}))

(def routes
  [["/room/:room-id/message" {:post {:handler send-message-handler}}]])
```

## The Solution

With kaiin, you annotate your effect registration with metadata:

```clojure
{::s/actions
 {:room/send-message
  {::s/handler (fn [_state _room-id username message]
                 [[::twk/patch-elements (views/message-bubble username message)]])

   ;; Kaiin metadata - generates POST /room/:room-id/message
   ::kaiin/path "/room/:room-id/message"
   ::kaiin/signals [:map [:username :string] [:message :string]]
   ::kaiin/dispatch [:room/send-message
                     [::kaiin/path-param :room-id]
                     [::kaiin/signal :username]
                     [::kaiin/signal :message]]
   ::kaiin/target [:* [:room [::kaiin/path-param :room-id] :*]]}}}
```

Then generate routes:

```clojure
(require '[ascolais.kaiin :as kaiin])

(def dispatch (s/create-dispatch [my-registry]))
(def routes (kaiin/routes dispatch))
;; => [["/room/:room-id/message" {:post {:handler <generated-fn>}}]]
```

## Installation

```clojure
;; deps.edn
{:deps {io.github.brianium/kaiin {:git/tag "v0.1.0" :git/sha "..."}}}
```

Peer dependencies (your application provides these):
- `ascolais/sandestin` - Effect dispatch system
- `ascolais/twk` - Datastar middleware (interprets `::twk/fx` responses)
- `ascolais/sfere` - Connection management (interprets `::sfere/broadcast` effects)

## Usage

### Basic Route Generation

```clojure
(require '[ascolais.kaiin :as kaiin]
         '[ascolais.sandestin :as s]
         '[reitit.ring :as rr])

;; Create dispatch from your registries
(def dispatch (s/create-dispatch [my-registry]))

;; Generate routes and compose with custom routes
(def app
  (rr/ring-handler
    (rr/router
      (into custom-routes (kaiin/routes dispatch)))))
```

### Metadata Keys

| Key | Required | Description |
|-----|----------|-------------|
| `::kaiin/path` | Yes | HTTP path with reitit-style params (e.g., `/room/:room-id/message`) |
| `::kaiin/method` | No | HTTP method (default: `:post`) |
| `::kaiin/signals` | Yes | Malli `:map` schema for expected Datastar signals |
| `::kaiin/dispatch` | Yes | Effect vector with token placeholders |
| `::kaiin/target` | No | Sfere connection pattern for broadcast |

### Token Types

Tokens are placeholders in `::kaiin/dispatch` and `::kaiin/target` that get replaced at request time:

```clojure
;; Extract from Datastar signals
[::kaiin/signal :username]           ;; (:username signals)
[::kaiin/signal [:user :name]]       ;; (get-in signals [:user :name])

;; Extract from URL path parameters
[::kaiin/path-param :room-id]        ;; (:room-id path-params)
```

### With vs Without Target

**With `::kaiin/target`** - Broadcasts to sfere connections:

```clojure
{::kaiin/dispatch [:room/send-message ...]
 ::kaiin/target [:* [:room [::kaiin/path-param :room-id] :*]]}
;; Generated handler wraps dispatch in ::sfere/broadcast
```

**Without `::kaiin/target`** - Effects go to the caller:

```clojure
{::kaiin/dispatch [:room/join ...]}
;; No target - action's returned effects dispatched to caller's SSE
```

### Validation

Kaiin validates metadata at router creation time:

- Signal tokens must reference keys in the `::kaiin/signals` schema
- Path-param tokens must reference parameters in `::kaiin/path`
- Duplicate path+method combinations are rejected

```clojure
;; This throws at router creation - :room-id not in path
{::kaiin/path "/message"
 ::kaiin/dispatch [:send [::kaiin/path-param :room-id] ...]}
```

## Example: Multi-Room Chat

See the demo in `dev/src/clj/demo/` for a complete example.

```clojure
;; Registry with kaiin metadata
(def room-registry
  {::s/actions
   {:room/send-message
    {::s/handler (fn [_state _room-id username message]
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

     ::kaiin/path "/room/:room-id/message"
     ::kaiin/signals [:map [:username :string] [:message :string]]
     ::kaiin/dispatch [:room/send-message
                       [::kaiin/path-param :room-id]
                       [::kaiin/signal :username]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:room [::kaiin/path-param :room-id] :*]]}}})

;; Application wiring
(def dispatch (s/create-dispatch [(twk/registry) (sfere/registry store) room-registry]))

(def app
  (-> (rr/ring-handler
        (rr/router
          (into custom-routes (kaiin/routes dispatch))
          {:data {:middleware [(twk/with-datastar sse-adapter dispatch)]}}))
      (rr/create-default-handler)))
```

## API Reference

### `(kaiin/routes dispatch)`
### `(kaiin/routes dispatch opts)`

Generate reitit route vectors from a sandestin dispatch function.

Options:
- `:prefix` - Path prefix for all generated routes

Returns a vector of route definitions for composition with other routes.

### `(kaiin/router dispatch)`
### `(kaiin/router dispatch opts)`

Generate a complete reitit router from a sandestin dispatch function.

Options:
- `:prefix` - Path prefix for all generated routes
- `:data` - Reitit route data merged into all routes

Use `routes` instead when composing with custom routes.

### `(kaiin/routes-from-metadata dispatch metadata-seq)`

Generate routes from a sequence of metadata maps. Useful for testing without a full sandestin registry.

## Specifications

Detailed specifications are in the `specs/` directory:

- [001-core-api](specs/001-core-api.md) - Primary API
- [002-registry-metadata](specs/002-registry-metadata.md) - Metadata schema
- [003-token-replacement](specs/003-token-replacement.md) - Token resolution
- [010-path-param-demo](specs/010-path-param-demo.md) - Multi-room demo

## Development

```bash
# Run tests
clj -X:test

# Start REPL with dev dependencies
clj -M:dev

# In REPL
(dev)      ;; Switch to dev namespace
(start)    ;; Start demo server on port 3000
(reload)   ;; Reload changed namespaces
```

## License

Copyright 2025 Brian Scaturro

Distributed under the Eclipse Public License version 1.0.
