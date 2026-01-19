# 008: Optional Target (Direct Response Routes)

**Status:** Complete
**Priority:** High
**Dependencies:** 007-action-handlers-for-broadcast

## Summary

Make `::kaiin/target` optional. When omitted, the action's returned effects go directly to the caller as `{::twk/fx [...]}` instead of being wrapped in sfere dispatch.

## Motivation

Currently kaiin requires `::kaiin/target` for every route, wrapping the dispatch in `::sfere/broadcast` or `::sfere/with-connection`. This works for routes that dispatch to stored connections, but not for:

1. **Direct response routes** (`/join`) - return effects to the requester, not to stored connections
2. **Complex multi-target routes** (`/leave`) - action can return sfere effects directly with custom exclude patterns

## Current Behavior

```clojure
;; Kaiin requires target, always wraps in sfere
{::kaiin/dispatch [:my/action ...]
 ::kaiin/target [:* [:lobby :*]]}

;; Generated handler returns:
{::twk/fx [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
            [:my/action ...]]]
 ::twk/with-open-sse? true}
```

## Proposed Behavior

When `::kaiin/target` is omitted:

```clojure
;; No target specified
{::kaiin/dispatch [:my/action ...]}

;; Action returns effects directly (not wrapped in sfere)
;; Generated handler returns:
{::twk/fx [<action-returned-effects>]
 ::twk/with-open-sse? true}
```

The action's returned effects become the `::twk/fx` value. Those effects can be:
- Direct twk effects (`::twk/patch-elements`, etc.)
- Sfere effects (`::sfere/broadcast`, etc.) - twk middleware dispatches these

## Use Cases

### Direct Response: /join

Action returns twk effects to the caller:

```clojure
{::s/actions
 {:lobby/join
  {::s/handler (fn [_state username]
                 [[::twk/patch-elements (views/lobby-ui username)]])

   ::kaiin/path "/join"
   ::kaiin/method :post
   ::kaiin/signals [:map [:username :string]]
   ::kaiin/dispatch [:lobby/join [::kaiin/signal :username]]}}}
   ;; No ::kaiin/target - effects go to caller
```

Generated handler returns:
```clojure
{::twk/fx [[::twk/patch-elements [:div#join-form ...]]]
 ::twk/with-open-sse? true}
```

### Complex Multi-Target: /leave

Action returns sfere effects with custom routing:

```clojure
{::s/actions
 {:lobby/leave
  {::s/handler (fn [_state username]
                 (let [user-key [::sfere/default-scope [:lobby username]]]
                   [;; Broadcast to others (exclude self)
                    [::sfere/broadcast {:pattern [:* [:lobby :*]]
                                        :exclude #{user-key}}
                     [::twk/patch-elements (views/participant-left username)
                      {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
                    ;; Remove from participant list (exclude self)
                    [::sfere/broadcast {:pattern [:* [:lobby :*]]
                                        :exclude #{user-key}}
                     [::twk/patch-elements ""
                      {twk/selector (str "#participant-" username)
                       twk/patch-mode twk/pm-remove}]]
                    ;; Close own connection
                    [::sfere/with-connection user-key
                     [::twk/close-sse]]]))

   ::kaiin/path "/leave"
   ::kaiin/method :post
   ::kaiin/signals [:map [:username :string]]
   ::kaiin/dispatch [:lobby/leave [::kaiin/signal :username]]}}}
   ;; No ::kaiin/target - action handles routing
```

Generated handler returns:
```clojure
{::twk/fx [[::sfere/broadcast {:pattern ... :exclude ...} ...]
           [::sfere/broadcast {:pattern ... :exclude ...} ...]
           [::sfere/with-connection ... [::twk/close-sse]]]
 ::twk/with-open-sse? true}
```

The twk middleware dispatches each effect, including the sfere effects.

## Implementation

### Handler Generation

```clojure
(defn generate-handler [dispatch metadata]
  (let [dispatch-template (::dispatch metadata)
        target-template (::target metadata)  ;; May be nil
        response-opts (::response-opts metadata)]
    (fn [request]
      (let [context {:signals (:signals request)
                     :path-params (:path-params request)}
            dispatch-vec (replace-tokens dispatch-template context)]

        (if target-template
          ;; With target: wrap in sfere effect
          (let [target-key (replace-tokens target-template context)
                sfere-effect (if (has-wildcard? target-key)
                               [::sfere/broadcast {:pattern target-key} dispatch-vec]
                               [::sfere/with-connection target-key dispatch-vec])]
            {::twk/fx [sfere-effect]
             ::twk/with-open-sse? true})

          ;; No target: return dispatch vector as effect for twk to dispatch
          {::twk/fx [dispatch-vec]
           ::twk/with-open-sse? true})))))
```

### Key Insight: Let TWK Handle Dispatch

Kaiin doesn't dispatch at request time. Instead, it returns the dispatch vector as an effect in `::twk/fx`. The twk middleware then dispatches the effect, which sandestin handles by running the action and returning its effects to the caller's SSE connection.

This is consistent with the with-target case where we return a sfere effect and let twk dispatch it.

## Metadata Schema Update

```clojure
(def metadata-schema
  [:map
   [::path :string]
   [::method {:optional true} :keyword]
   [::signals [:fn {:error/message "signals must be a :map schema"}
               #(and (vector? %) (= :map (first %)))]]
   [::dispatch [:vector :any]]
   [::target {:optional true} [:vector :any]]])  ;; Now optional
```

## Validation Changes

- Token validation for `::target` only runs if target is present
- `::dispatch` tokens still validated against signals/path-params

## Demo Migration

After implementation:

| Route | Before | After |
|-------|--------|-------|
| `GET /` | Custom | Custom (static page, not effects) |
| `POST /join` | Custom | Kaiin (no target) |
| `POST /sse` | Custom | Custom (connection establishment) |
| `POST /message` | Kaiin (with target) | Kaiin (with target) |
| `POST /leave` | Custom | Kaiin (no target) |

## Open Questions

1. **GET / (index page)** - Returns `{:body hiccup}` not `{::twk/fx [...]}`. Could add a `:body` response mode, but may be over-engineering. Keep as custom for now?

2. **Error handling** - If action dispatch fails, return 500 with error details?

## Related Specs

- [007-action-handlers-for-broadcast](./007-action-handlers-for-broadcast.md) - Actions return effects
- [004-handler-generation](./004-handler-generation.md) - Handler generation (needs update)
