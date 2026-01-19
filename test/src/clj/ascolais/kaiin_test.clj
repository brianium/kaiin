(ns ascolais.kaiin-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.kaiin :as kaiin]))

;; Path parameter extraction tests

(deftest extract-path-params-test
  (testing "extracts single parameter"
    (is (= #{:room-id} (kaiin/extract-path-params "/chat/:room-id/message"))))

  (testing "extracts multiple parameters"
    (is (= #{:user-id :section}
           (kaiin/extract-path-params "/user/:user-id/profile/:section"))))

  (testing "handles path with no parameters"
    (is (= #{} (kaiin/extract-path-params "/static/path"))))

  (testing "handles parameter at end of path"
    (is (= #{:id} (kaiin/extract-path-params "/items/:id"))))

  (testing "handles hyphenated parameters"
    (is (= #{:room-id :user-name}
           (kaiin/extract-path-params "/:room-id/:user-name")))))

;; Signal key extraction tests

(deftest extract-signal-keys-test
  (testing "extracts flat keys from map schema"
    (is (= #{:message :username}
           (kaiin/extract-signal-keys [:map [:message :string] [:username :string]]))))

  (testing "extracts nested keys"
    (is (= #{:user [:user :name] [:user :email]}
           (kaiin/extract-signal-keys
            [:map [:user [:map [:name :string] [:email :string]]]]))))

  (testing "handles mixed flat and nested"
    (is (= #{:message :user [:user :id]}
           (kaiin/extract-signal-keys
            [:map [:message :string] [:user [:map [:id :uuid]]]]))))

  (testing "handles deeply nested structures"
    (is (= #{:a [:a :b] [:a :b :c]}
           (kaiin/extract-signal-keys
            [:map [:a [:map [:b [:map [:c :string]]]]]]))))

  (testing "handles optional keys with property maps"
    (is (= #{:required :optional}
           (kaiin/extract-signal-keys
            [:map
             [:required :string]
             [:optional {:optional true} :string]])))))

;; Token extraction tests

(deftest extract-tokens-test
  (testing "extracts signal tokens from dispatch"
    (is (= {:signal-tokens (list [::kaiin/signal :message])
            :path-param-tokens ()}
           (kaiin/extract-tokens [:chat/send [::kaiin/signal :message]]))))

  (testing "extracts path-param tokens from dispatch"
    (is (= {:signal-tokens ()
            :path-param-tokens (list [::kaiin/path-param :room-id])}
           (kaiin/extract-tokens [:chat/send [::kaiin/path-param :room-id]]))))

  (testing "extracts mixed tokens"
    (is (= {:signal-tokens (list [::kaiin/signal :message])
            :path-param-tokens (list [::kaiin/path-param :room-id])}
           (kaiin/extract-tokens
            [:chat/send
             [::kaiin/path-param :room-id]
             [::kaiin/signal :message]]))))

  (testing "extracts nested signal paths"
    (is (= {:signal-tokens (list [::kaiin/signal [:user :name]])
            :path-param-tokens ()}
           (kaiin/extract-tokens [:user/update [::kaiin/signal [:user :name]]]))))

  (testing "extracts tokens from target vectors"
    (is (= {:signal-tokens ()
            :path-param-tokens (list [::kaiin/path-param :room-id])}
           (kaiin/extract-tokens [:* [:chat [::kaiin/path-param :room-id]]])))))

;; Signal token validation tests

(deftest validate-signal-tokens-test
  (testing "valid flat signal reference returns nil"
    (is (nil? (kaiin/validate-signal-tokens
               {::kaiin/signals [:map [:message :string]]
                ::kaiin/dispatch [:chat/send [::kaiin/signal :message]]
                ::kaiin/target [:* :*]}))))

  (testing "valid nested signal reference returns nil"
    (is (nil? (kaiin/validate-signal-tokens
               {::kaiin/signals [:map [:user [:map [:name :string]]]]
                ::kaiin/dispatch [:user/update [::kaiin/signal [:user :name]]]
                ::kaiin/target [:* :*]}))))

  (testing "invalid signal reference returns error"
    (let [errors (kaiin/validate-signal-tokens
                  {::kaiin/signals [:map [:message :string]]
                   ::kaiin/dispatch [:chat/send [::kaiin/signal :nonexistent]]
                   ::kaiin/target [:* :*]})]
      (is (= 1 (count errors)))
      (is (= :nonexistent (:path (first errors))))))

  (testing "invalid nested signal reference returns error"
    (let [errors (kaiin/validate-signal-tokens
                  {::kaiin/signals [:map [:user [:map [:name :string]]]]
                   ::kaiin/dispatch [:user/update [::kaiin/signal [:user :email]]]
                   ::kaiin/target [:* :*]})]
      (is (= 1 (count errors)))
      (is (= [:user :email] (:path (first errors)))))))

;; Path param token validation tests

(deftest validate-path-param-tokens-test
  (testing "valid path param reference returns nil"
    (is (nil? (kaiin/validate-path-param-tokens
               {::kaiin/path "/chat/:room-id/message"
                ::kaiin/dispatch [:chat/send [::kaiin/path-param :room-id]]
                ::kaiin/target [:* :*]}))))

  (testing "invalid path param reference returns error"
    (let [errors (kaiin/validate-path-param-tokens
                  {::kaiin/path "/chat/:room-id/message"
                   ::kaiin/dispatch [:chat/send [::kaiin/path-param :user-id]]
                   ::kaiin/target [:* :*]})]
      (is (= 1 (count errors)))
      (is (= :user-id (:param (first errors))))
      (is (= #{:room-id} (:available-params (first errors))))))

  (testing "path param in target is validated"
    (let [errors (kaiin/validate-path-param-tokens
                  {::kaiin/path "/chat/message"
                   ::kaiin/dispatch [:chat/send]
                   ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]})]
      (is (= 1 (count errors)))
      (is (= :room-id (:param (first errors)))))))

;; Complete metadata validation tests

(deftest validate-metadata-test
  (testing "valid metadata passes validation"
    (is (nil? (kaiin/validate-metadata
               {::kaiin/path "/chat/:room-id/message"
                ::kaiin/method :post
                ::kaiin/signals [:map [:message :string] [:username :string]]
                ::kaiin/dispatch [:chat/send
                                  [::kaiin/path-param :room-id]
                                  [::kaiin/signal :message]]
                ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]}))))

  (testing "missing required key throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid kaiin metadata"
         (kaiin/validate-metadata
          {::kaiin/path "/chat/message"
           ;; missing ::kaiin/signals
           ::kaiin/dispatch [:chat/send]
           ::kaiin/target [:* :*]}))))

  (testing "invalid signals schema throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid kaiin metadata"
         (kaiin/validate-metadata
          {::kaiin/path "/chat/message"
           ::kaiin/signals :string  ;; not a :map schema
           ::kaiin/dispatch [:chat/send]
           ::kaiin/target [:* :*]}))))

  (testing "invalid signal token reference throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid kaiin metadata"
         (kaiin/validate-metadata
          {::kaiin/path "/chat/message"
           ::kaiin/signals [:map [:message :string]]
           ::kaiin/dispatch [:chat/send [::kaiin/signal :nonexistent]]
           ::kaiin/target [:* :*]}))))

  (testing "invalid path param token reference throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid kaiin metadata"
         (kaiin/validate-metadata
          {::kaiin/path "/chat/message"  ;; no :room-id param
           ::kaiin/signals [:map [:message :string]]
           ::kaiin/dispatch [:chat/send [::kaiin/path-param :room-id]]
           ::kaiin/target [:* :*]})))))

;; Token resolution tests

(deftest resolve-token-test
  (testing "resolves signal token with keyword path"
    (is (= "hello"
           (kaiin/resolve-token [::kaiin/signal :message]
                                {:signals {:message "hello"}}))))

  (testing "resolves signal token with vector path"
    (is (= "Alice"
           (kaiin/resolve-token [::kaiin/signal [:user :name]]
                                {:signals {:user {:name "Alice"}}}))))

  (testing "resolves path-param token"
    (is (= "general"
           (kaiin/resolve-token [::kaiin/path-param :room-id]
                                {:path-params {:room-id "general"}}))))

  (testing "throws on missing signal value"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Missing signal value"
         (kaiin/resolve-token [::kaiin/signal :missing]
                              {:signals {:message "hello"}}))))

  (testing "throws on missing path param"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Missing path parameter"
         (kaiin/resolve-token [::kaiin/path-param :missing]
                              {:path-params {:room-id "general"}})))))

;; Token replacement tests

(deftest replace-tokens-test
  (testing "replaces tokens in dispatch vector"
    (is (= [:chat/send-message "general" "Hello, world!"]
           (kaiin/replace-tokens
            [:chat/send-message
             [::kaiin/path-param :room-id]
             [::kaiin/signal :message]]
            {:signals {:message "Hello, world!"}
             :path-params {:room-id "general"}}))))

  (testing "replaces tokens in target vector"
    (is (= [:* [:chat "general"]]
           (kaiin/replace-tokens
            [:* [:chat [::kaiin/path-param :room-id]]]
            {:path-params {:room-id "general"}}))))

  (testing "replaces nested signal paths"
    (is (= [:user/update "sess-123"]
           (kaiin/replace-tokens
            [:user/update [::kaiin/signal [:session :id]]]
            {:signals {:session {:id "sess-123"}}}))))

  (testing "replaces deeply nested tokens"
    (is (= [:scope [:room "lobby" "sess-123"]]
           (kaiin/replace-tokens
            [:scope [:room
                     [::kaiin/path-param :room-id]
                     [::kaiin/signal [:session :id]]]]
            {:signals {:session {:id "sess-123"}}
             :path-params {:room-id "lobby"}}))))

  (testing "preserves non-token values"
    (is (= [:chat/send "static" "general" 123]
           (kaiin/replace-tokens
            [:chat/send "static" [::kaiin/path-param :room-id] 123]
            {:path-params {:room-id "general"}}))))

  (testing "handles empty forms"
    (is (= [] (kaiin/replace-tokens [] {}))))

  (testing "handles forms with no tokens"
    (is (= [:chat/send "hello" 123]
           (kaiin/replace-tokens [:chat/send "hello" 123] {})))))

;; Wildcard detection tests

(deftest has-wildcard?-test
  (testing "detects wildcard at top level"
    (is (kaiin/has-wildcard? [:* :*])))

  (testing "detects wildcard in nested structure"
    (is (kaiin/has-wildcard? [:* [:chat "general"]])))

  (testing "returns false for no wildcards"
    (is (not (kaiin/has-wildcard? [:scope [:chat "general"]]))))

  (testing "detects deeply nested wildcard"
    (is (kaiin/has-wildcard? [:scope [:room [:* "lobby"]]]))))

;; Handler generation tests

(deftest generate-handler-test
  (testing "generates handler that returns broadcast for wildcard targets"
    (let [metadata {::kaiin/path "/chat/:room-id/message"
                    ::kaiin/method :post
                    ::kaiin/signals [:map [:message :string] [:username :string]]
                    ::kaiin/dispatch [:chat/send-message
                                      [::kaiin/path-param :room-id]
                                      [::kaiin/signal :username]
                                      [::kaiin/signal :message]]
                    ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]}
          handler (kaiin/generate-handler metadata)
          request {:path-params {:room-id "general"}
                   :signals {:message "Hello!" :username "alice"}}
          response (handler request)]
      (is (= {:ascolais.twk/fx [[:ascolais.sfere/broadcast
                                 {:pattern [:* [:chat "general"]]}
                                 [:chat/send-message "general" "alice" "Hello!"]]]
              :ascolais.twk/with-open-sse? true}
             response))))

  (testing "generates handler that returns with-connection for non-wildcard targets"
    (let [metadata {::kaiin/path "/user/:user-id/profile"
                    ::kaiin/method :post
                    ::kaiin/signals [:map [:name :string]]
                    ::kaiin/dispatch [:user/update-profile
                                      [::kaiin/path-param :user-id]
                                      [::kaiin/signal :name]]
                    ::kaiin/target [:default-scope [:user [::kaiin/path-param :user-id]]]}
          handler (kaiin/generate-handler metadata)
          request {:path-params {:user-id "alice"}
                   :signals {:name "Alice Smith"}}
          response (handler request)]
      (is (= {:ascolais.twk/fx [[:ascolais.sfere/with-connection
                                 [:default-scope [:user "alice"]]
                                 [:user/update-profile "alice" "Alice Smith"]]]
              :ascolais.twk/with-open-sse? true}
             response))))

  (testing "returns 400 for missing signal"
    (let [metadata {::kaiin/path "/chat/message"
                    ::kaiin/signals [:map [:message :string]]
                    ::kaiin/dispatch [:chat/send [::kaiin/signal :message]]
                    ::kaiin/target [:* :*]}
          handler (kaiin/generate-handler metadata)
          request {:path-params {}
                   :signals {}}  ;; missing :message
          response (handler request)]
      (is (= 400 (:status response)))
      (is (= "Missing signal value" (get-in response [:body :error])))))

  (testing "returns 400 for missing path param"
    (let [metadata {::kaiin/path "/chat/:room-id/message"
                    ::kaiin/signals [:map [:message :string]]
                    ::kaiin/dispatch [:chat/send [::kaiin/path-param :room-id]]
                    ::kaiin/target [:* :*]}
          handler (kaiin/generate-handler metadata)
          request {:path-params {}  ;; missing :room-id
                   :signals {:message "hello"}}
          response (handler request)]
      (is (= 400 (:status response)))
      (is (= "Missing path parameter" (get-in response [:body :error])))))

  (testing "merges custom response options"
    (let [metadata {::kaiin/path "/chat/message"
                    ::kaiin/signals [:map [:message :string]]
                    ::kaiin/dispatch [:chat/send [::kaiin/signal :message]]
                    ::kaiin/target [:* :*]
                    ::kaiin/response-opts {:ascolais.twk/with-open-sse? false}}
          handler (kaiin/generate-handler metadata)
          request {:path-params {}
                   :signals {:message "hello"}}
          response (handler request)]
      (is (= false (:ascolais.twk/with-open-sse? response))))))
