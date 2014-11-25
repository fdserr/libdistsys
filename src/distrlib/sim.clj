(ns distrlib.sim
  (:refer-clojure :exclude [shuffle])
  (:require [clojure.data.priority-map :refer (priority-map-keyfn)]))

(defrecord Message [time dest id])

(defrecord Timeout [time dest id])

(defn message
  [target & kvs]
  (let [m (->Message nil target nil)]
    (if kvs
      (apply assoc m kvs)
      m)))

(defn timeout
  [t & kvs]
  (let [m (->Timeout t nil nil)]
    (if kvs
      (apply assoc m kvs)
      m)))

(defn timeout?
  [t]
  (instance? Timeout t))

(let [a (atom 0)]
  (defn unique
    []
    (swap! a inc)))

(defn assoc-if-nil
  [m & kvs]
  (reduce (fn [m [k v]]
            (if (get m k)
              m
              (assoc m k v)))
          m
          (partition 2 kvs)))

(defn network-delay
  [seed]
  (+ (if (zero? (mod seed 2))
       1
       500) (mod seed 10)))

(defn fixup-events
  [node now events]
  (map (fn [e]
         (assoc (cond
                  (instance? Message e)
                  (assoc-if-nil e :time (+ (network-delay (hash [node now e])) 50 now))
                  (instance? Timeout e)
                  (-> e
                      (assoc-if-nil :dest node)
                      (update-in [:time] #(+ now %))) ; deliver to self
                  )
                :id (unique)))
       events))

(defrecord SimulatorState [^long time ^long seed node])
;;TODO stick this record into every actor callback arg
;;ensure that the run lets you prepopulate this state w/ whatever crap you want
;;perhaps a seed should be stored here, too, since we'd really like to have
;;deterministc randomness. We could include builtins like shuffles, RNGs, etc
;;probably need a decent hash mixing fn for longs too

(defn run
  "Takes a map from node names to actors"
  ([actors initial-events until-time]
   (run 22 actors initial-events until-time))
  ([seed actors initial-events until-time]
   (let [initial-node-states (into {} (map (fn [[node actor]] [node (actor)]) actors))
         initial-events' (->> initial-events
                              (fixup-events ::root -1)
                              (mapcat (fn [e] [(:id e) e])))]
     (loop [current-time 0
            prev-time 0
            iters 0
            current-node-states initial-node-states
            pending-events (apply priority-map-keyfn :time initial-events')]
       (if (and (not (neg? current-time))
                (< current-time until-time))
         (let [events-at-this-time (take-while #(= (:time %) current-time) (vals pending-events))
               [next-state new-events]
               (reduce (fn [[state events] e]
                         (let [node (:dest e)
                               actor (get actors node)
                               node-state (get state node)
                               ;_ (println "event" e "node" node "actor" (boolean actor) )
                               [node-state' new-events] (actor node-state (->SimulatorState current-time seed node) e)
                               fixed-events (fixup-events node current-time new-events)]
                           [(assoc state node node-state')
                            (into events fixed-events)]))
                       [current-node-states []]
                       events-at-this-time)
               next-events (into (apply dissoc pending-events (map :id events-at-this-time))
                                 (map (fn [e] [(:id e) e]) new-events))
               next-time (:time (first (vals next-events)) -1)]
           (recur next-time current-time (inc iters) next-state next-events))
         [prev-time current-node-states])))))


;;; TODO: make a visual environment that shows the execution traces rendered as graphs/timeseries
;;; Need a way to generate logs and view them efficiently on the trace. We'll do this by buffering each step in memory, and then flushing out to a log file (leveldb or sqlite). We'll be able to then serve up the log through another module, which initially will give you a table control that lets you scroll through all events & hover to get datastructure details
;;; Use https://highlightjs.org/usage/ for datastructure higlighting?
;;; Should capture every time an event runs, in order. Info includes the node, the message, the time, the start & end state, every log inside the actor, and all generated messages. Should use clojure.tools.logging but need to include richer tracking metadata to ensure the log is useful for the analyzer (in text or db?)
