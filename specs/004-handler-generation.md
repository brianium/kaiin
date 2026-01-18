# 004: Handler Generation

**Status:** Draft
**Priority:** High
**Dependencies:** 002-registry-metadata, 003-token-replacement, 005-sfere-integration

## Summary

Define how kaiin generates ring handlers from effect metadata. Each generated handler extracts request data, performs token replacement, wraps effects in sfere dispatch, and returns a twk-compatible response.

## Handler Structure

A generated handler is a ring handler function:

```clojure
(fn [request]
  ;; 1. Extract signals and path-params
  ;; 2. Replace tokens in ::kaiin/dispatch and ::kaiin/target
  ;; 3. Wrap effect in sfere dispatch based on target
  ;; 4. Return twk response
  )
```

## Generated Handler Behavior

### Step 1: Extract Request Data

```clojure
(let [signals (get request :signals)           ;; Parsed by twk middleware
      path-params (get-in request [:path-params])  ;; Provided by reitit
      context {:signals signals :path-params path-params}]
  ...)
```

**Note:** The application must use twk's `with-datastar` middleware to parse signals.

### Step 2: Token Replacement

```clojure
(let [dispatch-vec (replace-tokens (::kaiin/dispatch metadata) context)
      target-key (replace-tokens (::kaiin/target metadata) context)]
  ...)
```

### Step 3: Wrap in Sfere Dispatch

Based on whether `target-key` contains wildcards:

```clojure
(let [sfere-effect (if (has-wildcard? target-key)
                     [::sfere/broadcast {:pattern target-key} dispatch-vec]
                     [::sfere/with-connection target-key dispatch-vec])]
  ...)
```

### Step 4: Return Twk Response

```clojure
{::twk/fx [sfere-effect]
 ::twk/with-open-sse? true}  ;; Default: close connection after dispatch
```

## Complete Generated Handler

```clojure
(defn generate-handler [dispatch metadata]
  (fn [request]
    (let [;; Extract context
          signals (get request :signals)
          path-params (get request :path-params)
          context {:signals signals :path-params path-params}

          ;; Replace tokens
          dispatch-vec (replace-tokens (::kaiin/dispatch metadata) context)
          target-key (replace-tokens (::kaiin/target metadata) context)

          ;; Wrap in sfere
          sfere-effect (if (has-wildcard? target-key)
                         [::sfere/broadcast {:pattern target-key} dispatch-vec]
                         [::sfere/with-connection target-key dispatch-vec])]

      ;; Return twk response
      {::twk/fx [sfere-effect]
       ::twk/with-open-sse? true})))
```

## Example: Chat Message Handler

Given metadata:
```clojure
{::kaiin/path "/chat/:room-id/message"
 ::kaiin/method :post
 ::kaiin/signals [:map [:message :string] [:username :string]]
 ::kaiin/dispatch [:chat/send-message
                   [::kaiin/path-param :room-id]
                   [::kaiin/signal :username]
                   [::kaiin/signal :message]]
 ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]}
```

And request:
```clojure
{:request-method :post
 :uri "/chat/general/message"
 :path-params {:room-id "general"}
 :signals {:message "Hello!" :username "alice"}}
```

Generated handler returns:
```clojure
{::twk/fx [[::sfere/broadcast
            {:pattern [:* [:chat "general"]]}
            [:chat/send-message "general" "alice" "Hello!"]]]
 ::twk/with-open-sse? true}
```

## Handler Customization

### Custom Response Options

Allow per-effect customization via additional metadata:

```clojure
{::kaiin/response-opts {::twk/with-open-sse? false}}  ;; Keep connection open
```

Handler generation:
```clojure
(let [base-response {::twk/fx [sfere-effect]
                     ::twk/with-open-sse? true}
      custom-opts (::kaiin/response-opts metadata {})]
  (merge base-response custom-opts))
```

### Additional Effects

Some handlers may need to dispatch additional effects (e.g., clear input after send):

```clojure
{::kaiin/additional-fx [[::twk/patch-signals {:message ""}]]}
```

These would be appended to the `::twk/fx` vector. However, this might be better handled in the effect handler itself.

## Error Handling

### Token Resolution Errors

If token replacement fails (missing signal or path param):

```clojure
(try
  (let [dispatch-vec (replace-tokens ...)]
    {::twk/fx [...]})
  (catch ExceptionInfo e
    (if (= :token-resolution-error (:type (ex-data e)))
      {:status 400
       :body {:error "Missing required parameter"
              :details (ex-data e)}}
      (throw e))))
```

### Dispatch Errors

Errors during effect dispatch are handled by sandestin (returns `{:errors [...]}`) and twk (sends error events). Kaiin doesn't need special handling here.

## Middleware Stack

The generated router should be wrapped with necessary middleware:

```clojure
(defn router [dispatch opts]
  (let [routes (generate-routes dispatch opts)
        middleware (concat
                    [(twk/with-datastar ->sse dispatch)]  ;; Required for signals
                    (:middleware opts []))]
    (ring/router routes {:data {:middleware middleware}})))
```

**Note:** The application is responsible for providing the twk middleware configuration (SSE response adapter, etc.).

## Open Questions

1. **Middleware Responsibility:** Should kaiin automatically include twk middleware, or require the application to configure it?

2. **SSE Response Adapter:** Twk needs an `->sse-response` adapter (http-kit, ring, etc.). How does kaiin get this?
   - Option A: Require it in options map
   - Option B: Assume application wraps kaiin router with middleware
   - **Recommendation:** Option B - kaiin generates handlers that return twk response maps, application configures twk middleware

3. **System Injection:** Effect dispatch needs a `system` map. How is this provided?
   - Option A: Passed in options, stored in closure
   - Option B: Middleware adds to request
   - Option C: Use request itself as system (twk pattern)

4. **Connection Storage:** For sfere to work, the sfere store must be accessible. Should it be:
   - In the options map passed to `kaiin/router`
   - In middleware
   - In the sandestin system

5. **Response Format:** Should kaiin handlers return the twk response map directly, or should they return something kaiin-specific that gets transformed?

## Related Specs

- [003-token-replacement](./003-token-replacement.md) - Token replacement implementation
- [005-sfere-integration](./005-sfere-integration.md) - Sfere effect wrapping details
