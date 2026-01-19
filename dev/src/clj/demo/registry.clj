(ns demo.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.kaiin :as kaiin]
            [ascolais.twk :as twk]
            [demo.views :as views]))

(def room-registry
  "Registry for room actions with kaiin metadata.
   Actions return effects that sandestin dispatches to connections.

   This registry demonstrates path-param token usage:
   - [::kaiin/path-param :room-id] in dispatch vectors
   - [::kaiin/path-param :room-id] in target patterns"
  {::s/actions
   {:room/send-message
    {::s/description "Send a message to a specific room"
     ::s/schema [:tuple [:= :room/send-message] :string :string :string]
     ::s/handler (fn [_state _room-id username message]
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

     ;; Kaiin metadata - generates POST /room/:room-id/message
     ;; Demonstrates path-param in both dispatch and target
     ::kaiin/path "/room/:room-id/message"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string] [:message :string]]
     ::kaiin/dispatch [:room/send-message
                       [::kaiin/path-param :room-id]
                       [::kaiin/signal :username]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:room [::kaiin/path-param :room-id] :*]]}

    :room/join
    {::s/description "Join a room - returns room UI to caller"
     ::s/schema [:tuple [:= :room/join] :string :string]
     ::s/handler (fn [_state room-id username]
                   [[::twk/patch-elements (views/room-ui room-id username)]])

     ;; Kaiin metadata - generates POST /room/:room-id/join
     ;; No target - effects go directly to caller
     ::kaiin/path "/room/:room-id/join"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string]]
     ::kaiin/dispatch [:room/join
                       [::kaiin/path-param :room-id]
                       [::kaiin/signal :username]]}

    :room/leave
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

     ;; Kaiin metadata - generates POST /room/:room-id/leave
     ;; No target - action returns sfere effects directly
     ::kaiin/path "/room/:room-id/leave"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string]]
     ::kaiin/dispatch [:room/leave
                       [::kaiin/path-param :room-id]
                       [::kaiin/signal :username]]}}})
