(ns ogres.app.const)

(goog-define VERSION "latest")
(goog-define PATH "/release")
(goog-define SOCKET-URL "ws://localhost:5000/ws")

;; Effective WebSocket URL. Reads window.OGRES_SOCKET_URL at startup if set,
;; falling back to the compile-time SOCKET-URL value. Self-hosters can set
;; this in index.html without rebuilding the JS bundle.
(def socket-url
  (or (aget js/window "OGRES_SOCKET_URL") SOCKET-URL))

(def ^:const grid-size
  "The length, in pixels, of a single square in the scene grid. This
   correlates to 5 feet in this spatial system."
  70)

(def ^:const half-size
  "Half the length, in pixels, of a single square in the scene grid."
  35)
