# 004: Handler Generation

**Status:** Complete
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

Kaiin handlers are pure data transformers - they don't need access to system or sfere store. They extract request data, replace tokens, and return a twk response map. The actual effect dispatch happens in twk's `with-datastar` middleware, and sfere's registry has the store closed over in its effects.

```clojure
(defn generate-handler [metadata]
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

      ;; Return twk response - middleware handles dispatch
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

Kaiin does NOT configure middleware. The application is responsible for wrapping the kaiin router with twk's `with-datastar` middleware:

```clojure
;; Application code - kaiin just returns a router
(def kaiin-router (kaiin/router dispatch opts))

;; Application wraps with twk middleware
(def app
  (-> (ring/ring-handler
        (ring/router
          [["/" {:get home-handler}]
           ["/effects" kaiin-router]]))
      (twk/with-datastar ->sse-response dispatch)))
```

This keeps kaiin focused on route/handler generation and avoids coupling to specific HTTP servers (http-kit, ring-jetty, etc.).

## Open Questions

1. ~~**Middleware Responsibility:**~~ **RESOLVED** - Application configures twk middleware externally. Kaiin just generates handlers returning twk response maps.

2. ~~**SSE Response Adapter:**~~ **RESOLVED** - Application provides this when configuring twk middleware.

3. ~~**System Injection:**~~ **RESOLVED** - Kaiin handlers don't need the system. They return twk response maps; twk middleware constructs the system and dispatches.

4. ~~**Connection Storage:**~~ **RESOLVED** - Kaiin handlers don't need the sfere store. The sfere registry (installed by the app) has the store closed over in its effects.

5. ~~**Response Format:**~~ **RESOLVED** - Kaiin handlers return twk response maps directly (`{::twk/fx [...] ::twk/with-open-sse? true}`).

## Related Specs

- [003-token-replacement](./003-token-replacement.md) - Token replacement implementation
- [005-sfere-integration](./005-sfere-integration.md) - Sfere effect wrapping details
