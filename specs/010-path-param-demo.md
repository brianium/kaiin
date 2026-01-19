# 010: Path Param Demo - Multi-Room Chat

**Status:** Complete
**Priority:** Medium
**Dependencies:** 006-lobby-demo, 003-token-replacement

## Summary

Extend the demo application to demonstrate `[::kaiin/path-param ...]` token usage by adding multi-room chat functionality. Users can join named rooms and messages are broadcast only to participants in that room.

## Motivation

The current lobby demo exercises signal tokens but not path-param tokens. This spec adds a use case that demonstrates:

1. Path parameters in routes: `/room/:room-id/message`
2. `[::kaiin/path-param :room-id]` in dispatch vectors
3. `[::kaiin/path-param :room-id]` in target patterns

## Design

### URL Structure

```
GET  /                           # Landing page with room selector
POST /room/:room-id/join         # Join a specific room
POST /room/:room-id/sse          # Establish SSE for room (custom handler)
POST /room/:room-id/message      # Send message to room (kaiin-generated)
POST /room/:room-id/leave        # Leave room (kaiin-generated, no target)
```

### Connection Keys

Connections are scoped by room:

```clojure
;; Connection key format
[::sfere/default-scope [:room "general" "alice"]]
[::sfere/default-scope [:room "random" "bob"]]
```

### Target Pattern

Messages broadcast to all users in the same room:

```clojure
;; Target pattern with path-param
::kaiin/target [:* [:room [::kaiin/path-param :room-id] :*]]

;; After token replacement for room-id="general"
[:* [:room "general" :*]]
```

## Registry Changes

### Room Message Action

```clojure
{::s/actions
 {:room/send-message
  {::s/description "Send a message to a specific room"
   ::s/schema [:tuple [:= :room/send-message] :string :string :string]
   ::s/handler (fn [_state room-id username message]
                 [[::twk/patch-elements (views/message-bubble username message)
                   {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

   ;; Kaiin metadata - generates POST /room/:room-id/message
   ::kaiin/path "/room/:room-id/message"
   ::kaiin/method :post
   ::kaiin/signals [:map [:username :string] [:message :string]]
   ::kaiin/dispatch [:room/send-message
                     [::kaiin/path-param :room-id]    ;; From URL
                     [::kaiin/signal :username]       ;; From signals
                     [::kaiin/signal :message]]       ;; From signals
   ::kaiin/target [:* [:room [::kaiin/path-param :room-id] :*]]}}}
```

**Key points:**
- `::kaiin/path` contains `:room-id` parameter
- `::kaiin/dispatch` uses `[::kaiin/path-param :room-id]` to pass room to handler
- `::kaiin/target` uses `[::kaiin/path-param :room-id]` to scope broadcast

### Room Join Action

```clojure
{:room/join
 {::s/description "Join a room - returns room UI to caller"
  ::s/schema [:tuple [:= :room/join] :string :string]
  ::s/handler (fn [_state room-id username]
                [[::twk/patch-elements (views/room-ui room-id username)]])

  ::kaiin/path "/room/:room-id/join"
  ::kaiin/method :post
  ::kaiin/signals [:map [:username :string]]
  ::kaiin/dispatch [:room/join
                    [::kaiin/path-param :room-id]
                    [::kaiin/signal :username]]}}
  ;; No target - effects go to caller
```

### Room Leave Action

```clojure
{:room/leave
 {::s/description "Leave a room - handles broadcasts and connection cleanup"
  ::s/schema [:tuple [:= :room/leave] :string :string]
  ::s/handler (fn [_state room-id username]
                (let [user-key [:ascolais.sfere/default-scope [:room room-id username]]]
                  [;; Notify others in room
                   [:ascolais.sfere/broadcast {:pattern [:* [:room room-id :*]]
                                               :exclude #{user-key}}
                    [::twk/patch-elements (views/participant-left username)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
                   ;; Remove from participant list
                   [:ascolais.sfere/broadcast {:pattern [:* [:room room-id :*]]
                                               :exclude #{user-key}}
                    [::twk/patch-elements ""
                     {twk/selector (str "#participant-" username)
                      twk/patch-mode twk/pm-remove}]]
                   ;; Close leaving user's connection
                   [:ascolais.sfere/with-connection user-key
                    [::twk/close-sse]]]))

  ::kaiin/path "/room/:room-id/leave"
  ::kaiin/method :post
  ::kaiin/signals [:map [:username :string]]
  ::kaiin/dispatch [:room/leave
                    [::kaiin/path-param :room-id]
                    [::kaiin/signal :username]]}}
  ;; No target - action returns sfere effects directly
```

## Custom Handlers

### SSE Connection (Custom)

SSE establishment remains a custom handler because it returns `::sfere/key`:

```clojure
(defn room-sse-connect
  "POST /room/:room-id/sse - Establish SSE connection for room."
  [{:keys [signals path-params]}]
  (let [room-id (:room-id path-params)
        username (:username signals)]
    (if (or (nil? username) (empty? username))
      {:status 400 :body "Username required"}
      (let [user-key [::sfere/default-scope [:room room-id username]]
            store (::system/store system/*system*)
            existing-keys (sfere/list-connections store [:* [:room room-id :*]])
            existing-users (->> existing-keys
                                (map (fn [[_scope [_cat _room uname]]] uname))
                                (remove #{username}))]
        {::sfere/key [:room room-id username]
         ::twk/fx
         (concat
           (when (seq existing-users)
             [[::twk/patch-elements
               (into [:div] (map views/participant-item existing-users))
               {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]])
           [[::sfere/broadcast {:pattern [:* [:room room-id :*]]
                                :exclude #{user-key}}
             [::twk/patch-elements (views/participant-item username)
              {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]]
            [::sfere/broadcast {:pattern [:* [:room room-id :*]]}
             [::twk/patch-elements
              [:div.message.system-message (str username " joined " room-id)]
              {twk/selector "#messages" twk/patch-mode twk/pm-append}]]])}))))
```

### Custom Routes

```clojure
(def custom-routes
  [["/" {:name ::index :get handlers/index}]
   ["/room/:room-id/sse" {:name ::room-sse :post handlers/room-sse-connect}]])
```

## View Changes

### Landing Page

```clojure
(defn landing-page []
  [c/doctype-html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "UTF-8"}]
     [:title "Kaiin Demo - Rooms"]
     [:script {:src twk/CDN-url :type "module"}]
     [:style "..."]]
    [:body {:data-signals:username ""
            :data-signals:room "general"}
     [:h1 "Kaiin Demo - Multi-Room Chat"]
     [:div#join-form
      [:p "Enter your name and choose a room:"]
      [:input {:data-bind:username true :placeholder "Your name"}]
      [:select {:data-bind:room true}
       [:option {:value "general"} "General"]
       [:option {:value "random"} "Random"]
       [:option {:value "tech"} "Tech"]]
      [:button {:data-on:click "@post('/room/' + $room + '/join')"} "Join Room"]]]]])
```

### Room UI

```clojure
(defn room-ui [room-id username]
  [:div#join-form {:data-init (str "@post('/room/" room-id "/sse')")}
   [:h2 (str "Room: " room-id)]
   [:div#participants
    [:h3 "Participants:"]
    [:ul#participant-list
     [:li {:id (str "participant-" username)} username]]]
   [:div#messages
    [:div.message.system-message (str "Welcome to " room-id ", " username "!")]]
   [:div
    [:input {:data-bind:message true :placeholder "Type a message"}]
    [:button {:data-on:click (str "@post('/room/" room-id "/message')")} "Send"]]
   [:button {:data-on:click (str "@post('/room/" room-id "/leave')") :style "margin-top: 1rem;"}
    "Leave Room"]])
```

## Route Summary

| Route | Method | Source | Purpose |
|-------|--------|--------|---------|
| `/` | GET | Custom | Landing page with room selector |
| `/room/:room-id/join` | POST | Kaiin | Join room, get room UI |
| `/room/:room-id/sse` | POST | Custom | Establish SSE connection |
| `/room/:room-id/message` | POST | Kaiin | Broadcast message to room |
| `/room/:room-id/leave` | POST | Kaiin | Leave room, cleanup |

## Validation

At router creation time, kaiin validates:

1. `[::kaiin/path-param :room-id]` references `:room-id` which exists in path `/room/:room-id/message`
2. Signal tokens reference keys present in `::kaiin/signals` schema

Invalid configuration example:
```clojure
;; This would fail validation - :room-id not in path
{::kaiin/path "/message"
 ::kaiin/dispatch [:room/send-message [::kaiin/path-param :room-id] ...]}
;; Error: "Path param :room-id not found in route /message"
```

## Migration Strategy

This spec proposes **replacing** the lobby demo with a room-based demo rather than adding to it. The lobby demo can be preserved in git history but the new demo better demonstrates kaiin's capabilities.

Alternatively, both could coexist:
- `/` - Choose lobby or rooms
- `/lobby/*` - Original single-room lobby
- `/room/:room-id/*` - Multi-room chat

## Implementation Tasks

1. Update `demo.views` with room-aware view functions
2. Update `demo.registry` with room-scoped actions
3. Update `demo.handlers` with room SSE handler
4. Update `demo.app` with new route structure
5. Update landing page to support room selection
6. Test path-param token replacement end-to-end

## Success Criteria

- [x] Can join different rooms by name
- [x] Messages in room A do not appear in room B
- [x] Path-param tokens correctly resolve in dispatch vectors
- [x] Path-param tokens correctly resolve in target patterns
- [x] Validation rejects invalid path-param references at router creation time

## Open Questions

~~1. **Replace or extend?** Should this replace the lobby demo or coexist with it?~~
   - **RESOLVED:** Replaced. The room demo is a superset that better demonstrates kaiin.

~~2. **Dynamic room creation?** Should users be able to create arbitrary rooms?~~
   - **RESOLVED:** Yes - any room-id in the URL works.

## Related Specs

- [003-token-replacement](./003-token-replacement.md) - Token replacement algorithm
- [006-lobby-demo](./006-lobby-demo.md) - Original lobby demo being extended
- [007-action-handlers-for-broadcast](./007-action-handlers-for-broadcast.md) - Action vs effect pattern
