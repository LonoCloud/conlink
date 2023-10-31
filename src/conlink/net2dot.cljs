#!/usr/bin/env nbb

(ns conlink.net2dot
  (:require [clojure.string :as S]
            [clojure.pprint :refer [pprint]]
            [promesa.core :as P]
            [conlink.util :refer [parse-opts Eprintln Epprint fatal]]
            [conlink.core :as conlink]))
            
(def usage "
net2dot: convert conlink network config to GraphViz/dot representation.

Usage:
  net2dot [options]

Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]
  --network-file NETWORK-FILE...    Network config file
  --compose-file COMPOSE-FILE...    Docker compose file with network config
")

(def DEFAULT-PROPS "shape=box fontsize=12 style=filled penwidth=1")
(def CONLINK-PROPS  "style=\"rounded,filled\" fillcolor = \"#c1b5c7\" color = \"#9673a6\"")
(def BRIDGE-PROPS "style= filled fillcolor=\"#dae8fc\" color=\"#6c8ebf\"")
(def HOST-PROPS "style=filled fillcolor=\"#f5f5f5\" color=\"#666666\"")
(def TUNNEL-PROPS "fillcolor=\"#a5a5a5\" color=\"#888888\"")
(def CONTAINER-PROPS "style=\"rounded,filled\" fillcolor = \"#e1d5e7\" color = \"#9673a6\"")
(def SERVICE-PROPS (str CONTAINER-PROPS " fillcolor = \"#d1c5e7\" penwidth = 2"))
(def INTF-PROPS "width=0.1 height=0.1 fontsize=10 fillcolor=\"#ffbb9e\" color=\"#d7db00\"")
(def NIC-PROPS "fontsize=12 fillcolor=\"#ffbb9e\" color=\"#d7db00\"")

(set! conlink/INTF-MAX-LEN 100)

(defn dot-id [n]
  (-> n
      (S/replace #"[:]" "_COLON_")
      (S/replace #"[-]" "_DASH_")
      (S/replace #"[*]" "_STAR_")
      (S/replace #"[$]" "_DOLLAR_")
      (S/replace #"[{]" "_LCURLY_")
      (S/replace #"[}]" "_LCURLY_")
      (S/replace #"[ ]" "_SPACE_")))

(defn digraph [links tunnels]
  (let [veth-links (filter #(= :veth (:type %)) links)
        vlan-links (filter #(conlink/VLAN-TYPES (:type %)) links)]
    (S/join
      "\n"
      (flatten
        [(str "digraph D {")
         (str "  splines = true;")
         (str "  compound = true;")
         (str "  node [" DEFAULT-PROPS "];")

         ""
         " // host system"
         (str "  subgraph cluster_host {")
         (str "    label = \"host system\";")
         (str "    " HOST-PROPS ";")

         ""
         "    // main link nodes"
         (for [{:keys [dev dev-id]} links]
           (str "    " (dot-id dev-id) " [label=\"" dev "\" " INTF-PROPS "];"))

         ""
         "    // containers and their links/interfaces"
         (for [[container-name links] (group-by (comp :name :container) links)]
           [(str "    subgraph cluster_" (dot-id container-name) " {")
            (str "      label = \"" (:container-label (first links)) "\";")
            (if (:service (first links))
              (str "      " SERVICE-PROPS ";")
              (str "      " CONTAINER-PROPS ";"))
            (for [link links]
              (str "      " (dot-id (:dev-id link))))
            (str "    }")])

         ""
         "    // bridges, tunnels, veth connections"
         (str "    subgraph cluster_conlink {")
         (str "      label = \"conlink/network\";")
         (str "      " CONLINK-PROPS ";")
         (for [bridge (set (keep :bridge veth-links))
               :let [blinks (filter #(= bridge (:bridge %)) veth-links)]]
           [(str "      subgraph cluster_bridge_" (dot-id bridge) " {")
            (str "        label = \"" bridge "\";")
            (str "        " BRIDGE-PROPS ";")
            (str "        bridge_"  (dot-id bridge) " [shape=point style=invis];")
            (for [{:keys [dev-id outer-dev]} blinks]
              [(str "        " (dot-id outer-dev)
                   " [label=\"" outer-dev "\" " INTF-PROPS "];")
               (str "        " (dot-id dev-id) " -> " (dot-id outer-dev))])
            (for [{:keys [bridge outer-dev]} tunnels]
              (str "        " (dot-id outer-dev)
                   " [label=\"" outer-dev "\" " INTF-PROPS "];"))
            (str "      }")])
         (str "    }")

         ""
         "    // vlan/vtap links"
         (for [outer-dev (set (keep :outer-dev vlan-links))
               :let [olinks (filter #(= outer-dev (:outer-dev %)) vlan-links)]]
           [(str "    " (dot-id outer-dev) " [label=\"" outer-dev "\" " NIC-PROPS "];")
            (for [{:keys [dev-id outer-dev type vlanid vni ip]} olinks
                  :let [label (str (name type) (when vlanid
                                                 (str " " vlanid)))]]
              (str "    " (dot-id dev-id) " -> " (dot-id outer-dev)
                   " [label=\"" label "\"];"))])

         "  // end of host system"
         (str "  }")

         ""
         "  // remote hosts and tunnels links"
         (for [{:keys [outer-dev remote]} tunnels]
           [(str "  " (dot-id remote)
                 " [label=\"remote host '" remote "'\" " TUNNEL-PROPS "];")
            (str "  " (dot-id outer-dev) " -> " (dot-id remote)) ])

         "}\n"]))))

(defn enrich-link [{:as link :keys [service container]}]
  (let [name (if service
               (str "S_" service) #_(str "*_" service "_*")
               container)
        clabel (if service
                 (str "service '"service "'")
                 (str "container '" container "'"))
        container {:id "CID"
                   :pid 3
                   :index 1
                   :name name}]
    (merge
      (conlink/link-instance-enrich link container 2)
      {:container-label clabel})))

(defn enrich-tunnel [tunnel]
  (conlink/tunnel-instance-enrich tunnel 2))


(defn main
  [& args]
  (P/let
    [{:keys [verbose compose-file network-file]} (parse-opts usage args)
     _ (when (and (empty? network-file) (empty? compose-file))
         (fatal 2 "either --network-file or --compose-file is required"))
     network-config (P/-> (conlink/load-configs compose-file network-file)
                          (conlink/enrich-network-config))
     links (map enrich-link (:links network-config))
     tunnels (map enrich-tunnel (:tunnels network-config))
     dot-graph (digraph links tunnels)]
    (when verbose
      (Eprintln "Links:")
      (Epprint links)
      (Eprintln "Tunnels:")
      (Epprint tunnels))
    (println dot-graph)))
