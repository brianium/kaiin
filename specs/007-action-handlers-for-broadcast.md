# 007: Action Handlers for Broadcast

**Status:** Complete
**Priority:** High
**Dependencies:** 004-handler-generation, 005-sfere-integration, 006-lobby-demo

## Summary

The design mismatch discovered in 006-lobby-demo was a misunderstanding of sandestin's effect/action model. Kaiin-generated routes that broadcast to connections should use **actions** (which return effects to dispatch), not **effects** (which perform side effects directly).

## Sandestin Effect vs Action

### Effects
- Handler signature: `(fn [ctx sys & args])`
- Have access to dispatch context, SSE connection, etc.
- Perform side effects directly
- Don't return dispatchable data

### Actions
- Handler signature: `(fn [state & args])`
- `state` is the result of `system->state` (for twk: `{:signals ...}`)
- Return effects that sandestin automatically dispatches
- Pure functions of their arguments

## The Problem in 006-lobby-demo

The registry defined `:lobby/send-message` as an effect:

```clojure
{::s/effects  ;; ← WRONG
 {:lobby/send-message
  {::s/handler (fn [_ctx _sys username message]
                 [[::twk/patch-elements ...]])}}}  ;; returns data, but effects shouldn't
```

When sfere/broadcast dispatched `[:lobby/send-message ...]` to connections, sandestin treated it as an effect. Effect handlers don't have their return values dispatched - they're expected to perform side effects directly.

## The Solution

Use `::s/actions` for handlers that return effects to broadcast:

```clojure
{::s/actions  ;; ← CORRECT
 {:lobby/send-message
  {::s/handler (fn [_state username message]
                 [[::twk/patch-elements (views/message-bubble username message)
                   {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

   ::kaiin/path "/message"
   ::kaiin/method :post
   ::kaiin/signals [:map [:username :string] [:message :string]]
   ::kaiin/dispatch [:lobby/send-message
                     [::kaiin/signal :username]
                     [::kaiin/signal :message]]
   ::kaiin/target [:* [:lobby :*]]}}}
```

## Data Flow

1. **Request:** `POST /message` with signals `{:username "brian" :message "hello"}`

2. **Kaiin handler builds:**
   ```clojure
   {::twk/fx [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
               [:lobby/send-message "brian" "hello"]]]
    ::twk/with-open-sse? true}
   ```

3. **Twk middleware dispatches** the sfere/broadcast effect

4. **Sfere broadcasts** `[:lobby/send-message "brian" "hello"]` to each connection

5. **For each connection, sandestin:**
   - Sees `:lobby/send-message` is an action
   - Calls handler: `(handler state "brian" "hello")`
   - Gets return value: `[[::twk/patch-elements [:div.message ...]]]`
   - Dispatches those effects to the connection's SSE

6. **Browser receives** the patch-elements and updates the DOM

## Kaiin Implementation Status

Kaiin's current handler generation is **correct**. No changes needed to kaiin itself.

The fix is purely in how effects are registered:
- Use `::s/actions` for broadcast targets
- Use `::s/effects` for handlers that need direct access to ctx/sys

## Demo Migration

Update `demo/registry.clj`:

```clojure
(def lobby-registry
  {::s/actions  ;; Changed from ::s/effects
   {:lobby/send-message
    {::s/description "Send a message to all lobby participants"
     ::s/schema [:tuple [:= :lobby/send-message] :string :string]
     ::s/handler (fn [_state username message]
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

     ::kaiin/path "/message"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string] [:message :string]]
     ::kaiin/dispatch [:lobby/send-message
                       [::kaiin/signal :username]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:lobby :*]]}}})
```

Remove `/message` from custom routes in `demo/app.clj` - kaiin will generate it.

## When to Use Effects vs Actions with Kaiin

| Use Case | Registry Key | Handler Returns |
|----------|--------------|-----------------|
| Broadcast message to group | `::s/actions` | Effects to dispatch |
| Send to specific connection | `::s/actions` | Effects to dispatch |
| Complex logic needing ctx | `::s/effects` | Nothing (performs side effects) |
| Connection establishment | Custom handler | `{::sfere/key [...]}` |

**Rule of thumb:** If kaiin is generating the route and broadcasting/routing to connections, use `::s/actions`.

## Open Questions

None - the model is now clear.

## Related Specs

- [004-handler-generation](./004-handler-generation.md) - Handler generation (unchanged)
- [005-sfere-integration](./005-sfere-integration.md) - Sfere effect wrapping (unchanged)
- [006-lobby-demo](./006-lobby-demo.md) - Where the problem was discovered
