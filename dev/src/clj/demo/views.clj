(ns demo.views
  (:require [ascolais.twk :as twk]
            [dev.onionpancakes.chassis.core :as c]))

(def styles
  "body { font-family: system-ui, sans-serif; max-width: 800px; margin: 0 auto; padding: 2rem; }
   #room { margin-top: 1rem; }
   #participants { margin-bottom: 1rem; }
   #participant-list { list-style: none; padding: 0; display: flex; gap: 0.5rem; flex-wrap: wrap; }
   #participant-list li { background: #e0e0e0; padding: 0.25rem 0.5rem; border-radius: 4px; }
   #messages { border: 1px solid #ccc; padding: 1rem; height: 300px; overflow-y: auto; margin-bottom: 1rem; }
   .message { margin-bottom: 0.5rem; }
   .system-message { color: #666; font-style: italic; }
   input, select { padding: 0.5rem; margin-right: 0.5rem; }
   button { padding: 0.5rem 1rem; cursor: pointer; }
   .room-header { display: flex; align-items: center; gap: 1rem; }
   .room-header h2 { margin: 0; }")

(defn landing-page
  "Landing page with room selector."
  []
  [c/doctype-html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "UTF-8"}]
     [:title "Kaiin Demo - Rooms"]
     [:script {:src twk/CDN-url :type "module"}]
     [:style styles]]
    [:body {:data-signals:username ""
            :data-signals:room "general"
            :data-signals:message ""}
     [:h1 "Kaiin Demo - Multi-Room Chat"]
     [:div#join-form
      [:p "Enter your name and choose a room:"]
      [:input {:data-bind:username true :placeholder "Your name"}]
      [:select {:data-bind:room true}
       [:option {:value "general"} "General"]
       [:option {:value "random"} "Random"]
       [:option {:value "tech"} "Tech"]]
      [:button {:data-on:click "$room && $username && @post('/room/' + $room + '/join')"}
       "Join Room"]]]]])

(defn room-ui
  "Room UI shown after joining. Triggers SSE connection."
  [room-id username]
  [:div#join-form {:data-init (str "@post('/room/" room-id "/sse')")}
   [:div.room-header
    [:h2 (str "Room: " room-id)]]
   [:div#participants
    [:h3 "Participants:"]
    [:ul#participant-list
     [:li {:id (str "participant-" username)} username]]]
   [:div#messages
    [:div.message.system-message (str "Welcome to " room-id ", " username "!")]]
   [:div
    [:input {:data-bind:message true :placeholder "Type a message"}]
    [:button {:data-on:click (str "@post('/room/" room-id "/message')")} "Send"]]
   [:button {:data-on:click (str "@post('/room/" room-id "/leave')") :style "margin-top: 1rem;"}
    "Leave Room"]])

(defn participant-item [username]
  [:li {:id (str "participant-" username)} username])

(defn message-bubble [username message]
  [:div.message [:strong username] ": " message])

(defn participant-left [username]
  [:div.message.system-message (str username " left the room")])
