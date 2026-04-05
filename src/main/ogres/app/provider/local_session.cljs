(ns ogres.app.provider.local-session
  (:require [cognitect.transit :as transit]
            [datascript.core :as ds]
            [ogres.app.const :refer [VERSION]]
            [ogres.app.hooks :as hooks]
            [ogres.app.provider.idb :as idb]
            [ogres.app.provider.state :as state]
            [ogres.app.segment :as seg]
            [ogres.app.serialize :refer [reader writer]]
            [ogres.app.vec :as vec]
            [uix.core :as uix :refer [defui]]))

(def ^:private channel-name "ogres.app")

(defui host-listeners
  "Mounts on the host tab. Broadcasts every DataScript transaction to any
   open player view tabs via BroadcastChannel, and emits the host's current
   camera state when the Focus button is pressed."
  []
  (let [conn (uix/use-context state/context)
        [ch set-ch] (uix/use-state nil)]

    ;; Open BroadcastChannel and broadcast all DataScript transactions as diffs.
    (uix/use-effect
     (fn []
       (let [channel (js/BroadcastChannel. channel-name)]
         (set-ch channel)
         (ds/listen! conn ::local-broadcast
           (fn [{:keys [tx-data]}]
             (.postMessage channel (transit/write writer {:type :tx :data tx-data}))))
         (fn []
           (ds/unlisten! conn ::local-broadcast)
           (.close channel)
           (set-ch nil))))
     [conn])

    ;; When the host presses Focus, broadcast the world-space center of the host's
    ;; view so each player can independently adjust for their own viewport size.
    ;; This mirrors the real multiplayer logic in events.cljs :session/focus.
    (hooks/use-subscribe :session/focus
      (uix/use-callback
       (fn []
         (when (some? ch)
           (let [user   (ds/entity @conn [:db/ident :user])
                 camera (:user/camera user)
                 scene  (-> camera :camera/scene :db/id)
                 point  (or (:camera/point camera) vec/zero)
                 scale  (or (:camera/scale camera) 1)
                 bounds (or (:user/bounds user) seg/zero)
                 ;; World-space center of the host's view (same formula as multiplayer)
                 center (vec/add point (vec/div (seg/midpoint bounds) scale))]
             (.postMessage ch (transit/write writer
                                {:type     :focus
                                 :scene-id scene
                                 :center   center
                                 :scale    scale})))))
       [conn ch])))

(defn ^:private build-player-db
  "Given the host's DB (read from shared IndexedDB), creates a modified DB
   suitable for the player view: a fresh player entity with its own camera
   (initialized at the same scene/position as the host) is added, and the
   host entity loses its :user identity so that host camera movements
   (which arrive as transactions keyed by entity ID) don't affect the
   player's independent camera."
  [host-db]
  (let [user    (ds/entity host-db [:db/ident :user])
        host-id (:db/id user)
        camera  (:user/camera user)
        scene   (-> camera :camera/scene :db/id)
        point   (or (:camera/point camera) vec/zero)
        scale   (or (:camera/scale camera) 1)]
    (ds/db-with host-db
      [;; Retract :user identity from host first so the new player entity
       ;; can claim it without DataScript merging it into the host entity.
       [:db/retract host-id :db/ident :user]
       ;; New camera entity for the player (starts at host's current view)
       [:db/add "player-cam" :camera/scene scene]
       [:db/add "player-cam" :camera/point point]
       [:db/add "player-cam" :camera/scale scale]
       ;; New player entity with its own camera and :user identity
       [:db/add "player" :db/ident :user]
       [:db/add "player" :user/host false]
       [:db/add "player" :user/ready true]
       [:db/add "player" :session/status :connected]
       [:db/add "player" :user/color "blue"]
       [:db/add "player" :user/camera "player-cam"]
       [:db/add "player" :user/cameras "player-cam"]
       ;; Update root/user to point to the player entity
       [:db/add [:db/ident :root] :root/user "player"]])))

(defui player-listeners
  "Mounts on the player view tab (?local-player). Reads the current game
   state from the shared IndexedDB on mount, then listens for transaction
   diffs and focus signals from the host tab via BroadcastChannel."
  []
  (let [conn    (uix/use-context state/context)
        read-db (idb/use-reader "app")
        [ready? set-ready] (uix/use-state false)]

    ;; On mount: load the host's state from shared IDB, create a player entity
    ;; with its own independent camera, and reset the DataScript connection.
    (uix/use-effect
     (fn []
       (-> (read-db VERSION)
           (.then
            (fn [record]
              (when (some? record)
                (let [host-db  (-> (transit/read reader (.-data record))
                                   (ds/conn-from-datoms state/schema)
                                   (ds/db))
                      player-db (build-player-db host-db)]
                  (ds/reset-conn! conn player-db)
                  (set-ready true)))))))
     [conn read-db])

    ;; Once state is loaded, open a BroadcastChannel to receive incremental
    ;; transaction diffs and focus signals from the host tab.
    (uix/use-effect
     (fn []
       (when ready?
         (let [ch (js/BroadcastChannel. channel-name)]
           (set! (.-onmessage ch)
                 (fn [event]
                   (let [msg (transit/read reader (.-data event))]
                     (case (:type msg)
                       ;; Apply scene/token/mask/etc. changes from host.
                       ;; These target scene-level entities (by ID) and do not
                       ;; touch the player's own user/camera entity.
                       :tx
                       (ds/transact! conn (:data msg))

                       ;; Host pressed Focus: snap player camera to host's view,
                       ;; adjusting the camera point for the player's own viewport size.
                       :focus
                       (let [{:keys [scene-id center scale]} msg
                             user     (ds/entity @conn [:db/ident :user])
                             bounds   (or (:user/bounds user) seg/zero)
                             ;; center is world-space midpoint of host's view; subtract
                             ;; half the player's viewport (in world units) to get the
                             ;; correct top-left camera point for this player's screen.
                             point    (vec/sub center (vec/div (seg/midpoint bounds) scale))
                             cams     (:user/cameras user)
                             existing (->> cams
                                          (filter #(= (-> % :camera/scene :db/id) scene-id))
                                          first)]
                         (if (some? existing)
                           (ds/transact! conn
                             [[:db/add (:db/id existing) :camera/point point]
                              [:db/add (:db/id existing) :camera/scale scale]
                              [:db/add [:db/ident :user] :user/camera (:db/id existing)]])
                           (ds/transact! conn
                             [{:db/id        "focus-cam"
                               :camera/scene scene-id
                               :camera/point point
                               :camera/scale scale}
                              [:db/add [:db/ident :user] :user/cameras "focus-cam"]
                              [:db/add [:db/ident :user] :user/camera "focus-cam"]])))

                       nil))))
           (fn [] (.close ch)))))
     [conn ready?])))
