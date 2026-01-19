# 006: Lobby Demo

**Status:** Active
**Priority:** High
**Dependencies:** 001-core-api, 002-registry-metadata, 004-handler-generation, 005-sfere-integration

## Summary

Port the sfere lobby demo to use kaiin conventions. This demonstrates the separation between connection establishment (custom handlers) and effect dispatch (kaiin-generated routes).

## Original Sfere Demo

The sfere demo implements a real-time chat lobby with:
- **GET /** - Initial page with join form
- **POST /join** - Returns lobby UI with `data-init` for SSE
- **POST /sse** - Establishes persistent SSE connection (`::sfere/key`)
- **POST /message** - Broadcasts message to all lobby members
- **POST /leave** - Closes connection, notifies others

## Kaiin Port Strategy

### What Kaiin Generates

Kaiin generates routes for simple effect dispatch - single target, no exclusions:

- **POST /message** - Broadcast message to all lobby participants

### What Remains Custom

Connection establishment and complex multi-target handlers:

- **GET /** - Initial page (static HTML)
- **POST /join** - Returns lobby UI
- **POST /sse** - Establishes persistent connection (`::sfere/key`)
- **POST /leave** - Complex: multiple broadcasts with exclusions, then close connection

The `leave` handler requires different sfere routing for different parts (broadcast with exclude to others, with-connection to self). This doesn't fit kaiin's "one target per route" model, so it stays custom.

## Registry with Kaiin Metadata

```clojure
(ns demo.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.kaiin :as kaiin]
            [ascolais.twk :as twk]
            [demo.views :as views]))

(def lobby-registry
  {::s/effects
   {:lobby/send-message
    {::s/description "Send a message to all lobby participants"
     ::s/schema [:tuple [:= :lobby/send-message] :string :string]
     ::s/handler (fn [_ctx _sys username message]
                   ;; Return effects to dispatch to the connection
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

     ;; Kaiin metadata
     ::kaiin/path "/message"
     ::kaiin/method :post
     ::kaiin/signals [:map
                      [:username :string]
                      [:message :string]]
     ::kaiin/dispatch [:lobby/send-message
                       [::kaiin/signal :username]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:lobby :*]]}}})
```

This is the only kaiin-generated route. The effect handler returns twk effects that get dispatched to each connection matching the target pattern.

## Custom Handlers

```clojure
(ns demo.handlers
  (:require [ascolais.sfere :as sfere]
            [ascolais.twk :as twk]
            [demo.views :as views]
            [demo.system :as system]))

(defn index
  "GET / - Serve the initial lobby page."
  [_request]
  {:body (views/lobby-page)})

(defn join
  "POST /join - Validate username and return lobby UI.
   The lobby UI includes data-init that triggers /sse."
  [{:keys [signals]}]
  (let [username (:username signals)]
    (if (or (nil? username) (empty? username))
      {:status 400 :body "Username required"}
      {::twk/with-open-sse? true
       ::twk/fx [[::twk/patch-elements (views/lobby-ui username)]]})))

(defn sse-connect
  "POST /sse - Establish persistent SSE connection.
   This is triggered by data-init in lobby-ui."
  [{:keys [signals]}]
  (let [username (:username signals)]
    (if (or (nil? username) (empty? username))
      {:status 400 :body "Username required"}
      (let [user-key [::sfere/default-scope [:lobby username]]
            store (::system/store system/*system*)
            existing-keys (sfere/list-connections store [:* [:lobby :*]])
            existing-users (->> existing-keys
                                (map (fn [[_scope [_cat uname]]] uname))
                                (remove #{username}))]
        {::sfere/key [:lobby username]  ;; Store this connection
         ::twk/fx
         (concat
           ;; Send existing participants to the new user
           (when (seq existing-users)
             [[::twk/patch-elements
               (into [:div] (map views/participant-item existing-users))
               {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]])
           ;; Broadcast new user to others
           [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                                :exclude #{user-key}}
             [::twk/patch-elements (views/participant-item username)
              {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]]
            ;; Broadcast join message to all
            [::sfere/broadcast {:pattern [:* [:lobby :*]]}
             [::twk/patch-elements
              [:div.message.system-message (str username " joined the lobby")]
              {twk/selector "#messages" twk/patch-mode twk/pm-append}]]])}))))

(defn leave
  "POST /leave - Leave the lobby.
   Complex handler: broadcasts to others (with exclude), then closes own connection.
   This doesn't fit kaiin's single-target model, so it's a custom handler."
  [{:keys [signals]}]
  (let [username (:username signals)
        user-key [::sfere/default-scope [:lobby username]]]
    {::twk/with-open-sse? true
     ::twk/fx
     [;; Broadcast leave message to others (exclude self)
      [::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{user-key}}
       [::twk/patch-elements (views/participant-left username)
        {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
      ;; Remove from participant list for others (exclude self)
      [::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{user-key}}
       [::twk/patch-elements ""
        {twk/selector (str "#participant-" username)
         twk/patch-mode twk/pm-remove}]]
      ;; Close the leaving user's connection
      [::sfere/with-connection user-key
       [::twk/close-sse]]]}))
```

## Views (Unchanged from Sfere Demo)

```clojure
(ns demo.views
  (:require [ascolais.twk :as twk]
            [chassis.core :as c]))

(defn lobby-page []
  [c/doctype-html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "UTF-8"}]
     [:title "Kaiin Demo - Lobby"]
     [:script {:src twk/CDN-url :type "module"}]
     [:style "..."]]  ;; Same styles as sfere demo
    [:body {:data-signals:username ""
            :data-signals:message ""}
     [:h1 "Kaiin Demo - Lobby"]
     [:div#join-form
      [:p "Enter your name to join the lobby:"]
      [:input {:data-bind:username true :placeholder "Your name"}]
      [:button {:data-on:click "@post('/join')"} "Join Lobby"]]
     [:div#lobby {:style "display:none"}
      [:div#participants
       [:h3 "In Lobby:"]
       [:ul#participant-list]]
      [:div#messages]
      [:div
       [:input {:data-bind:message true :placeholder "Type a message"}]
       [:button {:data-on:click "@post('/message')"} "Send"]]
      [:button {:data-on:click "@post('/leave')" :style "margin-top: 1rem;"}
       "Leave Lobby"]]]]])

(defn lobby-ui [username]
  [:div#join-form {:data-init "@post('/sse')"}
   [:div#participants
    [:h3 "In Lobby:"]
    [:ul#participant-list
     [:li {:id (str "participant-" username)} username]]]
   [:div#messages
    [:div.message.system-message (str "Welcome to the lobby, " username "!")]]
   [:div
    [:input {:data-bind:message true :placeholder "Type a message"}]
    [:button {:data-on:click "@post('/message')"} "Send"]]
   [:button {:data-on:click "@post('/leave')" :style "margin-top: 1rem;"}
    "Leave Lobby"]])

(defn participant-item [username]
  [:li {:id (str "participant-" username)} username])

(defn message-bubble [username message]
  [:div.message [:strong username] ": " message])

(defn participant-left [username]
  [:div.message.system-message (str username " left the lobby")])
```

## Application Wiring

```clojure
(ns demo.app
  (:require [reitit.ring :as rr]
            [reitit.ring.middleware.parameters :as rmp]
            [ascolais.sandestin :as s]
            [ascolais.twk :as twk]
            [ascolais.sfere :as sfere]
            [ascolais.kaiin :as kaiin]
            [demo.registry :refer [lobby-registry]]
            [demo.handlers :as handlers]
            [org.httpkit.server :as hk]))

;; Custom routes (connection establishment + complex handlers)
(def custom-routes
  [["/" {:name ::index :get handlers/index}]
   ["/join" {:name ::join :post handlers/join}]
   ["/sse" {:name ::sse :post handlers/sse-connect}]
   ["/leave" {:name ::leave :post handlers/leave}]])

;; Create dispatch with all registries
(defn create-dispatch [store]
  (s/create-dispatch
   [(twk/registry)
    (sfere/registry store)
    lobby-registry]))

;; Create the full router
(defn create-router [dispatch]
  (rr/router
   [custom-routes
    (kaiin/router dispatch)]  ;; Kaiin adds /message route
   {:data {:middleware [rmp/parameters-middleware]}}))

;; Create the app handler
(defn create-app [store dispatch]
  (-> (rr/ring-handler
       (create-router dispatch)
       (rr/create-default-handler))
      (twk/with-datastar hk/->sse-response dispatch)))

;; System startup
(defn start-system []
  (let [store (sfere/store {:type :atom})
        dispatch (create-dispatch store)
        handler (create-app store dispatch)
        server (hk/run-server handler {:port 3000})]
    {:store store
     :dispatch dispatch
     :server server}))
```

## Route Summary

| Route | Method | Source | Purpose |
|-------|--------|--------|---------|
| `/` | GET | Custom | Initial page |
| `/join` | POST | Custom | Validate, return lobby UI |
| `/sse` | POST | Custom | Establish persistent connection |
| `/message` | POST | Kaiin | Broadcast message to all |
| `/leave` | POST | Custom | Complex: notify others, close connection |

## Key Differences from Original Sfere Demo

1. **Effect handlers return effects, not responses** - Kaiin wraps them in sfere dispatch automatically

2. **Route generation is automatic** - `kaiin/router` inspects `::kaiin/*` metadata and generates routes

3. **Separation of concerns**:
   - Connection routes (custom): return `::sfere/key`
   - Complex multi-target routes (custom): need excludes or multiple sfere targets
   - Simple dispatch routes (kaiin): single target, no exclusions

4. **When to use kaiin vs custom**:
   - Use kaiin for simple "broadcast this effect to a target" patterns
   - Use custom handlers when you need excludes, multiple targets, or complex routing logic

## Open Questions

None - this spec is a concrete example of the patterns defined in other specs.

## Related Specs

- [001-core-api](./001-core-api.md) - Router composition
- [002-registry-metadata](./002-registry-metadata.md) - Kaiin metadata format
- [004-handler-generation](./004-handler-generation.md) - How handlers are generated
- [005-sfere-integration](./005-sfere-integration.md) - Target semantics
