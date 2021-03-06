(ns netrunner.core
  (:require [netrunner.utils :refer [set-zone remove-card merge-costs has? find-first to-keyword]]
            [netrunner.macros :refer [gfn fx]]))

(def cards (atom {}))

(defn defcard [name & r]
  (swap! cards assoc name (apply hash-map r)))

(defn card-def [card]
  (@cards (:title card)))

(defn trigger-event [state side event & args]
  state)

(defn allowed? [state side action & args]
  true)

(defn say [state side {:keys [user text]}]
  (let [author (or user (get-in state [side :user]))]
    (update-in state [:log] #(conj % {:user author :text text}))))

(defn system-msg [state side text]
  (let [username (get-in state [side :user :username])]
    (say state side {:user "__system__" :text (str username " " text ".")})))

(defn resolve-msg [state side msg card options]
  (let [m (if (string? msg)
            msg
            (msg state side card options))]
    (system-msg state side (str (when-let [t (:title card)]
                                  (str "uses " t))
                                " to " m))))

(defn can-pay? [state side costs]
  (every? #(or (>= (or (get-in state [side (first %)]) 0) (last %))
               (= (first %) :memory))
          (merge-costs costs)))

(defn update-values [state side deltas f]
  (loop [ds (partition 2 deltas)
         s state]
    (if (empty? ds)
      s
      (let [d (first ds)]
          (recur (rest ds)
                 (update-in s [side (first d)] #(f % (last d))))))))

(defn pay [state side costs]
  (update-values state side costs #(- (or %1 0) %2)))

(defn gain [state side & deltas]
  (update-values state side deltas #(+ (or %1 0) %2)))

(defn lose [state side & deltas]
  (update-values state side deltas #(if (= %2 :all)
                                      0
                                      (let [v (- (or %1 0) %2)]
                                        (if (= %1 :memory)
                                          v
                                          (max 0 v))))))

(defn card-init [state side card]
  (let [{:keys [in-play]} (card-def card)]
    (as-> state s
      (if in-play (apply gain s side in-play) state))))

(defn deactivate [state side card]
  (let [{:keys [in-play leave-play]} (card-def card)]
    (as-> state s
      (if leave-play (leave-play s side card nil) s)
      (if in-play (apply lose s side in-play) s))))

(defn res
  ([state side ability] (res state side ability nil nil))
  ([state side ability card] (res state side ability card nil))
  ([state side {:keys [req costs additional-costs] :as ability} card options]
   (let [total-costs (concat costs additional-costs)
         {:keys [msg effect]} ability]
     (if (and (can-pay? state side total-costs)
              (or (not req) (req state side card options)))
       (as-> state s
         (pay s side total-costs)
         (if msg (resolve-msg s side msg card options) s)
         (if effect (effect s side card options) s))
       state))))

(defn move [state card zone]
  (-> state
      (update-in zone #(conj (vec %) (assoc card :zone zone)))
      (update-in (:zone card) #(remove-card card %))))

(defn move-cards [state side from to n]
  (let [moved (set-zone to (take n (get-in state [side :deck])))]
     (-> state
         (update-in [side to] #(concat % moved))
         (update-in [side from] #(drop n %)))))

(gfn draw [n]
     (move-cards side :deck :hand n))

(gfn mill [n]
     (move-cards side :deck :discard n))

(gfn purge [])

(defn trash [state side card]
  (-> state
      (move card [side :discard])
      (deactivate side card)))

(defn get-card [state card zone]
  (find-first #(= (:cid %) (:cid card)) (get-in state zone)))

(defn play-instant
  ([state side card] (play-instant state side card nil))
  ([state side card {:keys [extra-costs] :as options}]
   (let [ability (-> (card-def card)
                     (merge {:costs (concat [:credit (:cost card)] extra-costs)})
                     (update-in [:additional-costs] concat (when (has? card :subtype "Double") [:click 1]))
                     (update-in [:additional-costs] concat (when (has? card :subtype "Triple") [:click 2])))]
     (if (has? card :subtype "Current")
       (as-> state s
         (trash s side (get-in s [:current 0]))
         (res s side ability card)
         (move s card [:current]))
       (as-> state s
         (move s card [side :play-area])
         (res s side ability card)
         (move s (get-card s card [side :play-area]) [side :discard]))))))


(gfn corp-install [card])

(gfn runner-install [card]
     (card-init side card)
     (move card [:runner :rig (if (:facedown options) :facedown (to-keyword (:type card)))]))

(gfn click-credit []
     (res side {:costs [:click 1] :effect (fx (gain :credit 1))}))

(gfn click-draw []
     (res side {:costs [:click 1] :effect (fx (draw 1))}))

(gfn click-purge []
     (res side {:costs [:click 3] :effect (fx (purge))}))

(gfn remove-tag []
     (res side {:costs [:click 1 :credit 2] :effect (fx (lose :tag 1))}))

(defn play
  ([state side card] (play state side card nil))
  ([state side card {:keys [server] :as options}]
   (let [cdef (@cards (:title card))]
     (case (:type card)
       ("Event" "Operation") (play-instant state side card {:extra-costs [:click 1]})
       ("Hardware" "Resource" "Program") (runner-install state side card {:extra-costs [:click 1]})
       ("ICE" "Upgrade" "Asset" "Agenda") (corp-install state side card {:extra-costs [:click 1] :server server})
       state))))
