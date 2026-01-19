(ns demo.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.kaiin :as kaiin]
            [ascolais.twk :as twk]
            [demo.views :as views]))

(def lobby-registry
  "Registry for lobby actions with kaiin metadata.
   Actions return effects that sandestin dispatches to connections."
  {::s/actions
   {:lobby/send-message
    {::s/description "Send a message to all lobby participants"
     ::s/schema [:tuple [:= :lobby/send-message] :string :string]
     ::s/handler (fn [_state username message]
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

     ;; Kaiin metadata - generates POST /message route
     ::kaiin/path "/message"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string] [:message :string]]
     ::kaiin/dispatch [:lobby/send-message
                       [::kaiin/signal :username]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:lobby :*]]}

    :lobby/join
    {::s/description "Join the lobby - returns effects to caller (no target)"
     ::s/schema [:tuple [:= :lobby/join] :string]
     ::s/handler (fn [_state username]
                   [[::twk/patch-elements (views/lobby-ui username)]])

     ;; Kaiin metadata - generates POST /join route
     ;; No ::kaiin/target - effects go directly to caller
     ::kaiin/path "/join"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string]]
     ::kaiin/dispatch [:lobby/join [::kaiin/signal :username]]}

    :lobby/leave
    {::s/description "Leave the lobby - complex routing with exclude patterns"
     ::s/schema [:tuple [:= :lobby/leave] :string]
     ::s/handler (fn [_state username]
                   (let [user-key [:ascolais.sfere/default-scope [:lobby username]]]
                     [;; Broadcast leave message to others (exclude self)
                      [:ascolais.sfere/broadcast {:pattern [:* [:lobby :*]]
                                                  :exclude #{user-key}}
                       [::twk/patch-elements (views/participant-left username)
                        {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
                      ;; Remove from participant list for others (exclude self)
                      [:ascolais.sfere/broadcast {:pattern [:* [:lobby :*]]
                                                  :exclude #{user-key}}
                       [::twk/patch-elements ""
                        {twk/selector (str "#participant-" username)
                         twk/patch-mode twk/pm-remove}]]
                      ;; Close the leaving user's connection
                      [:ascolais.sfere/with-connection user-key
                       [::twk/close-sse]]]))

     ;; Kaiin metadata - generates POST /leave route
     ;; No ::kaiin/target - action returns sfere effects directly
     ::kaiin/path "/leave"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string]]
     ::kaiin/dispatch [:lobby/leave [::kaiin/signal :username]]}}})
