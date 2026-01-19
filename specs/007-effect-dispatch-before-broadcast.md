# 007: Effect Dispatch Before Broadcast

**Status:** Active
**Priority:** High
**Dependencies:** 004-handler-generation, 005-sfere-integration, 006-lobby-demo

## Summary

Fix the design mismatch discovered in 006-lobby-demo: kaiin-generated handlers must dispatch effects through sandestin to get resulting twk effects, then broadcast those effects to connections.

## Problem Statement

The current implementation (as specified in 004-handler-generation) generates handlers that return:

```clojure
{::twk/fx [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
            [:lobby/send-message "brian" "hello"]]]
 ::twk/with-open-sse? true}
```

This doesn't work because:
1. `::sfere/broadcast` sends the nested effect directly to each connection's SSE
2. The dispatch vector `[:lobby/send-message ...]` is never executed
3. The effect handler's returned twk effects (e.g., `[[::twk/patch-elements ...]]`) are never produced

**What we need:** The effect should be dispatched first, and the *resulting* twk effects should be broadcast.

## Solution: Dispatch-Then-Broadcast

Kaiin-generated handlers should:
1. Build the dispatch vector from request data (unchanged)
2. **NEW:** Dispatch the effect through sandestin to get resulting effects
3. Wrap the *resulting effects* in sfere/broadcast
4. Return the twk response

### Before (Current Broken Behavior)

```
Request → Build dispatch vector → Wrap in broadcast → Return
                                  └─ [:lobby/send-message "brian" "hello"]
                                     ↑ This never gets dispatched!
```

### After (Fixed Behavior)

```
Request → Build dispatch vector → Dispatch effect → Wrap results in broadcast → Return
                                  └─ sandestin executes effect handler
                                     └─ Returns [[::twk/patch-elements ...]]
                                        └─ These get broadcast to connections
```

## Implementation

### Handler Generation

```clojure
(defn generate-handler [dispatch metadata]
  (fn [request]
    (let [;; Extract context
          signals (get request :signals)
          path-params (get request :path-params)
          context {:signals signals :path-params path-params}

          ;; Replace tokens in dispatch vector
          dispatch-vec (replace-tokens (::kaiin/dispatch metadata) context)
          target-key (replace-tokens (::kaiin/target metadata) context)

          ;; NEW: Dispatch the effect to get resulting effects
          result (dispatch dispatch-vec)

          ;; Extract effects from dispatch result
          ;; Sandestin returns {:result [...effects...]} on success
          resulting-fx (get result :result [])

          ;; Wrap each resulting effect in sfere dispatch
          sfere-effects (if (has-wildcard? target-key)
                          (mapv (fn [fx] [::sfere/broadcast {:pattern target-key} fx])
                                resulting-fx)
                          (mapv (fn [fx] [::sfere/with-connection target-key fx])
                                resulting-fx))]

      {::twk/fx sfere-effects
       ::twk/with-open-sse? true})))
```

### Key Change: Dispatch Injection

`generate-handler` now takes `dispatch` as its first argument. The `routes` and `router` functions already receive dispatch for introspection—now they also close over it for runtime use.

```clojure
(defn routes
  ([dispatch] (routes dispatch {}))
  ([dispatch opts]
   (let [metadata-seq (extract-kaiin-metadata dispatch opts)]
     (mapv (fn [m] (route-from-metadata dispatch m)) metadata-seq))))

(defn- route-from-metadata [dispatch metadata]
  [(::path metadata)
   {(::method metadata :post) (generate-handler dispatch metadata)}])
```

## Example Flow

### Effect Registration

```clojure
{:lobby/send-message
 {::s/description "Send a message to all lobby participants"
  ::s/schema [:tuple [:= :lobby/send-message] :string :string]
  ::s/handler (fn [_ctx _sys username message]
                [[::twk/patch-elements (views/message-bubble username message)
                  {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

  ;; Kaiin metadata
  ::kaiin/path "/message"
  ::kaiin/method :post
  ::kaiin/signals [:map [:username :string] [:message :string]]
  ::kaiin/dispatch [:lobby/send-message
                    [::kaiin/signal :username]
                    [::kaiin/signal :message]]
  ::kaiin/target [:* [:lobby :*]]}}
```

### Request Processing

1. **Request arrives:** `POST /message` with signals `{:username "brian" :message "hello"}`

2. **Token replacement:**
   - `dispatch-vec` → `[:lobby/send-message "brian" "hello"]`
   - `target-key` → `[:* [:lobby :*]]`

3. **Effect dispatch:**
   ```clojure
   (dispatch [:lobby/send-message "brian" "hello"])
   ;; => {:result [[::twk/patch-elements [:div.message [:strong "brian"] ": " "hello"]
   ;;               {...selector...}]]}
   ```

4. **Wrap in broadcast:**
   ```clojure
   [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
     [::twk/patch-elements [:div.message [:strong "brian"] ": " "hello"]
      {...selector...}]]]
   ```

5. **Return response:**
   ```clojure
   {::twk/fx [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
               [::twk/patch-elements [:div.message ...] {...}]]]
    ::twk/with-open-sse? true}
   ```

6. **Twk middleware processes** the response, dispatching the sfere/broadcast effect which sends the twk/patch-elements to all lobby connections.

## Dispatch Context

The effect handler receives `(fn [ctx sys & args] ...)`. When kaiin dispatches effects:

- `ctx` is minimal (no `:sse` key since we're not in an SSE context)
- `sys` comes from sandestin's system configuration

The effect should be a pure function that returns effects based on its arguments, not relying on request context. If request context is needed, it should be passed as signal arguments.

## Error Handling

### Dispatch Errors

If sandestin dispatch returns errors:

```clojure
(let [result (dispatch dispatch-vec)]
  (if (:errors result)
    {:status 500
     :body {:error "Effect dispatch failed"
            :details (:errors result)}}
    {::twk/fx (wrap-in-sfere (:result result) target-key)
     ::twk/with-open-sse? true}))
```

### Empty Results

If the effect returns no effects (empty vector or nil):

```clojure
(let [resulting-fx (get result :result [])]
  (if (empty? resulting-fx)
    {:status 204}  ;; No content to broadcast
    {::twk/fx (wrap-in-sfere resulting-fx target-key)
     ::twk/with-open-sse? true}))
```

## Migration from 006-lobby-demo

Once this spec is implemented, the lobby demo can use kaiin-generated routes:

### Before (Custom Handler)

```clojure
;; demo/handlers.clj
(defn send-message [{:keys [signals]}]
  (let [username (:username signals)
        message (:message signals)]
    {::twk/with-open-sse? true
     ::twk/fx
     [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
       [::twk/patch-elements (views/message-bubble username message)
        {twk/selector "#messages" twk/patch-mode twk/pm-append}]]]}))

;; demo/app.clj - custom route
["/message" {:name ::message :post handlers/send-message}]
```

### After (Kaiin-Generated)

```clojure
;; demo/registry.clj - effect with kaiin metadata
{:lobby/send-message
 {::s/handler (fn [_ctx _sys username message]
                [[::twk/patch-elements (views/message-bubble username message)
                  {twk/selector "#messages" twk/patch-mode twk/pm-append}]])
  ::kaiin/path "/message"
  ::kaiin/method :post
  ::kaiin/signals [:map [:username :string] [:message :string]]
  ::kaiin/dispatch [:lobby/send-message
                    [::kaiin/signal :username]
                    [::kaiin/signal :message]]
  ::kaiin/target [:* [:lobby :*]]}}

;; demo/app.clj - kaiin generates the route
(into custom-routes (kaiin/routes dispatch))
;; custom-routes no longer includes /message
```

## Testing

### Unit Test

```clojure
(deftest generate-handler-dispatches-effect
  (let [dispatch (fn [v]
                   (when (= (first v) :test/effect)
                     {:result [[::twk/patch-elements [:div "result"]]]}))
        metadata {::kaiin/dispatch [:test/effect [::kaiin/signal :arg]]
                  ::kaiin/target [:* [:test :*]]}
        handler (generate-handler dispatch metadata)
        response (handler {:signals {:arg "value"}})]

    (is (= {::twk/fx [[::sfere/broadcast {:pattern [:* [:test :*]]}
                       [::twk/patch-elements [:div "result"]]]]
            ::twk/with-open-sse? true}
           response))))
```

## Open Questions

1. **Multiple effects:** If the handler returns multiple effects, should each be wrapped in a separate broadcast, or should they all go in one broadcast?

   **Proposal:** Each effect gets its own broadcast wrapper. This matches how sfere/broadcast works (one nested effect per broadcast).

2. **Dispatch context:** What context should be passed to the dispatched effect? The handler doesn't have an SSE connection.

   **Proposal:** Minimal context. Effects used with kaiin should be pure functions of their arguments.

## Related Specs

- [004-handler-generation](./004-handler-generation.md) - Original handler generation spec (needs update)
- [005-sfere-integration](./005-sfere-integration.md) - Sfere effect wrapping
- [006-lobby-demo](./006-lobby-demo.md) - Where the problem was discovered
