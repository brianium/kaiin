(ns demo.handlers
  (:require [ascolais.sfere :as sfere]
            [ascolais.twk :as twk]
            [demo.views :as views]
            [demo.system :as system]))

(defn index
  "GET / - Serve the landing page with room selector."
  [_request]
  {:status 200
   :headers {"content-type" "text/html"}
   :body (views/landing-page)})

(defn room-sse-connect
  "POST /room/:room-id/sse - Establish persistent SSE connection for a room.
   This is triggered by data-init in room-ui."
  [{:keys [signals path-params]}]
  (tap> {:handler :room-sse-connect :signals signals :path-params path-params})
  (let [room-id (:room-id path-params)
        username (:username signals)]
    (tap> {:handler :room-sse-connect :room-id room-id :username username})
    (if (or (nil? username) (empty? username))
      (do
        (tap> {:handler :room-sse-connect :result :username-required})
        {:status 400 :body "Username required"})
      (let [user-key [::sfere/default-scope [:room room-id username]]
            store (:store system/*system*)
            existing-keys (sfere/list-connections store [:* [:room room-id :*]])
            existing-users (->> existing-keys
                                (map (fn [[_scope [_cat _room uname]]] uname))
                                (remove #{username}))
            response {::sfere/key [:room room-id username]
                      ::twk/fx
                      (concat
                       ;; Send existing participants to the new user
                       (when (seq existing-users)
                         [[::twk/patch-elements
                           (into [:div] (map views/participant-item existing-users))
                           {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]])
                       ;; Broadcast new user to others in the room
                       [[::sfere/broadcast {:pattern [:* [:room room-id :*]]
                                            :exclude #{user-key}}
                         [::twk/patch-elements (views/participant-item username)
                          {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]]
                        ;; Broadcast join message to all in room
                        [::sfere/broadcast {:pattern [:* [:room room-id :*]]}
                         [::twk/patch-elements
                          [:div.message.system-message (str username " joined " room-id)]
                          {twk/selector "#messages" twk/patch-mode twk/pm-append}]]])}]
        (tap> {:handler :room-sse-connect :result :success :response response})
        response))))
