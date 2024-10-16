#!/usr/bin/env nbb

;; Copyright (c) 2023, Viasat, Inc
;; Licensed under MPL 2.0

(ns net2dot
  (:require [clojure.string :as S]
            [clojure.pprint :refer [pprint]]
            [cljs-bean.core :refer [->clj ->js]]
            ["fs" :as fs]
            ["ts-graphviz" :refer [Digraph Subgraph toDot Node Edge]]))

;; Reads JSON network config from stdin and outputs to dot (GraphViz)

(def DEFAULT-PROPS {:shape "box" :fontsize 14 :style "filled" :penwidth 1})
(def CONLINK-PROPS {:fillcolor "#c5d1e7" :color "#7396a0" :style "rounded,filled" :penwidth 3})
(def CON-PROPS     {:fillcolor "#dfe8f1" :color "#7396a6" :style "rounded,filled"})
(def SVC-PROPS     {:fillcolor "#c1d0d7" :color "#7396a6" :style "rounded,filled" :penwidth 2})
(def BRIDGE-PROPS  {:fillcolor "#c8badc" :color "#6e509f"})
(def HOST-PROPS    {:fillcolor "#f5f5f5" :color "#666666"})
(def INTF-PROPS    {:fillcolor "#e8e8c8" :color "#af6b4e" :fontsize 10 :width 0.1 :height 0.1})
(def NIC-PROPS     {:fillcolor "#e8e8c8" :color "#af6b4e" :fontsize 14})
(def TUN-PROPS     {:fillcolor "#a5f5a5" :color "#888888"})

(defn dot-id [n]
  (-> (name n)
      (S/replace #"[:]" "_COLON_")
      (S/replace #"[-]" "_DASH_")
      (S/replace #"[*]" "_STAR_")
      (S/replace #"[$]" "_DOLLAR_")
      (S/replace #"[{]" "_LCURLY_")
      (S/replace #"[}]" "_RCURLY_")
      (S/replace #"[ ]" "_SPACE_")))

(defn node-props [label props]
  (merge DEFAULT-PROPS props {:label label}))

(defn digraph [props]
  (Digraph. (->js {:splines true :compound true})))

(defn subgraph [parent id label props]
  (let [n (Subgraph. id (->js (node-props label props)))]
    ^obj (.addSubgraph parent n)
    n))

(defn node [parent id label props]
  (let [n (Node. id (->js (node-props label props)))]
    ^obj (.addNode parent n)
    n))

(defn edge [parent idA idB label props] 
  (let [n (Edge. #js [idA idB] (->js (node-props label props)))]
    ^obj (.addEdge parent n)
    n))

(defn render [network-config]
  (let [graph (digraph {:splines true :compound true})
        host (subgraph graph "cluster_host" "host system" HOST-PROPS)
        conlink (subgraph host "cluster_conlink" "conlink/network" CONLINK-PROPS)
        links (->> network-config :services vals (map :links) (apply concat))
        bridges (reduce
                  #(->> (subgraph conlink (str "cluster_bridge_" %2)
                                  %2 BRIDGE-PROPS)
                        (assoc %1 %2))
                  {} (keys (:bridges network-config)))
        services (reduce
                   #(->> (subgraph host (str "cluster_service_" (dot-id %2))
                                   (str "service '" (name %2) "'") SVC-PROPS)
                         (assoc %1 %2))
                   {} (keys (:services network-config)))
        containers (reduce
                     #(->> (subgraph host (str "cluster_container_" (dot-id %2))
                                     (str "container '" (name %2) "'") CON-PROPS)
                           (assoc %1 %2))
                     {} (keys (:containers network-config)))]

    (doseq [link links]
      (let [{:keys [service container dev outer-dev bridge base]} link
            cname (or service container)
            cnode (get (if service services containers) (keyword cname))
            dev-id (str cname ":" (name dev))
            in-intf (node cnode (dot-id dev-id) dev INTF-PROPS)]
        (when (#{:conlink :host} (keyword base))
          (let [outer-dev (or outer-dev
                              (str (if service (str service "_1") container)
                                   "-" (name dev)))
                out-id (str "out-" outer-dev)
                out-parent (condp = (keyword base)
                             :conlink (get bridges (keyword (:bridge bridge)))
                             :host host)
                {:keys [type vlanid]} link
                [elabel iprops] (if (= "host" base)
                                  [(str (name type) (when vlanid
                                                      (str " " vlanid)))
                                   NIC-PROPS]
                                  ["" INTF-PROPS])
                out-intf (node out-parent (dot-id out-id) outer-dev iprops)
                edge (edge graph in-intf out-intf elabel {})]
            true))))

    (doseq [{:keys [type remote bridge vni]} (:tunnels network-config)]
      (let [br (get bridges bridge)
            rt (node graph (dot-id remote)
                     (str "remote host " remote) TUN-PROPS)
            intf (node br (dot-id (str type "-" vni))
                       (str type "-" vni) INTF-PROPS)]
        (edge graph intf rt "" {})))

    (toDot graph)))
    
(let [stdin (fs/readFileSync "/dev/stdin" "utf8")
      network-config (->clj (js/JSON.parse stdin))
      dot-graph (render network-config)]
  (println dot-graph))
