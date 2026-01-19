(ns demo.handlers
  (:require [ascolais.sfere :as sfere]
            [ascolais.twk :as twk]
            [demo.views :as views]
            [demo.system :as system]))

(defn index
  "GET / - Serve the initial lobby page."
  [_request]
  {:status 200
   :headers {"content-type" "text/html"}
   :body (views/lobby-page)})

(defn join
  "POST /join - Validate username and return lobby UI.
   The lobby UI includes data-on-load that triggers /sse."
  [{:keys [signals] :as request}]
  (tap> {:handler :join :signals signals :request-keys (keys request)})
  (let [username (:username signals)]
    (tap> {:handler :join :username username})
    (if (or (nil? username) (empty? username))
      (do
        (tap> {:handler :join :result :username-required})
        {:status 400 :body "Username required"})
      (let [response {::twk/with-open-sse? true
                      ::twk/fx [[::twk/patch-elements (views/lobby-ui username)]]}]
        (tap> {:handler :join :result :success :response response})
        response))))

(defn sse-connect
  "POST /sse - Establish persistent SSE connection.
   This is triggered by data-init in lobby-ui."
  [{:keys [signals]}]
  (tap> {:handler :sse-connect :signals signals :system system/*system*})
  (let [username (:username signals)]
    (tap> {:handler :sse-connect :username username})
    (if (or (nil? username) (empty? username))
      (do
        (tap> {:handler :sse-connect :result :username-required})
        {:status 400 :body "Username required"})
      (let [user-key [::sfere/default-scope [:lobby username]]
            store (:store system/*system*)
            existing-keys (sfere/list-connections store [:* [:lobby :*]])
            existing-users (->> existing-keys
                                (map (fn [[_scope [_cat uname]]] uname))
                                (remove #{username}))
            response {::sfere/key [:lobby username]
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
                          {twk/selector "#messages" twk/patch-mode twk/pm-append}]]])}]
        (tap> {:handler :sse-connect :result :success :response response})
        response))))

(defn send-message
  "POST /message - Send a message to everyone in lobby."
  [{:keys [signals]}]
  (tap> {:handler :send-message :signals signals})
  (let [username (:username signals)
        message (:message signals)]
    (tap> {:handler :send-message :username username :message message})
    (if (or (nil? message) (empty? message))
      (do
        (tap> {:handler :send-message :result :message-required})
        {:status 400 :body "Message required"})
      (let [response {::twk/with-open-sse? true
                      ::twk/fx
                      [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
                        [::twk/patch-elements (views/message-bubble username message)
                         {twk/selector "#messages" twk/patch-mode twk/pm-append}]]]}]
        (tap> {:handler :send-message :result :success :response response})
        response))))

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
