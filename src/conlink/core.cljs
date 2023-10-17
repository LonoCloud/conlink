#!/usr/bin/env nbb

(ns conlink.core
  (:require [clojure.string :as S]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj ->js]]
            [conlink.util :refer [parse-opts Eprintln Epprint fatal
                                  trim indent interpolate-walk deep-merge
                                  spawn read-file load-config]]
            [conlink.addrs :as addrs]
            #_["dockerode$default" :as Docker]))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Docker (js/require "dockerode"))

(def usage "
conlink: advanced container layer 2/3 linking/networking.

Usage:
  conlink [options]

General Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]
  --bridge-mode BRIDGE-MODE         Bridge mode (ovs or linux) to use for
                                    broadcast domains
                                    [default: ovs]
  --network-file NETWORK-FILE...    Network config file
  --compose-file COMPOSE-FILE...    Docker compose file with network config
  --compose-project NAME            Docker compose project name for resolving
                                    link :service keys (if conlink is a
                                    compose service then this defaults to
                                    the current compose project name)
  --docker-socket PATH              Docker socket to listen to
                                    [default: /var/run/docker.sock]
  --podman-socket PATH              Podman socket to listen to
                                    [default: /var/run/podman/podman.sock]
")


(def OVS-START-CMD (str "/usr/share/openvswitch/scripts/ovs-ctl start"
                        " --system-id=random --no-mlockall --delete-bridges"))

(def ctx (atom {:error #(apply Eprintln "ERROR" %&)
                :warn  #(apply Eprintln "WARNING:" %&)
                :log   Eprintln
                :info  list}))

(defn json-str [obj]
  (js/JSON.stringify (->js obj)))

(def conjv (fnil conj []))

(defn load-configs
  "Load network configs from a list of compose file paths and a list
  of network config file paths. Network configs in compose files are
  under the 'x-network' top-level key or as an 'x-network' property of
  services. The network configs are merged together into a single
  network configuration that is returned."
  [comp-cfgs net-cfgs]
  (P/let [comp-cfgs (P/all (map load-config comp-cfgs))
          xnet-cfgs (mapcat #(into [(:x-network %)]
                                   (map :x-network (-> % :services vals)))
                            comp-cfgs)
          net-cfgs (P/all (map load-config net-cfgs))
          net-cfg (reduce deep-merge {} (concat xnet-cfgs net-cfgs))]
    net-cfg))

(defn gen-network-state
  "Generate network state/context from network configuration. This
  restructures link configuration into top-level keys: :domains,
  :containers, and :services that provide a more efficient structure
  for looking up runtime status/state of those aspects of the
  configuration."
  [net-cfg]
  (reduce (fn [cfg {:as l :keys [service container interface domain]}]
            (assert domain (str "No domain specified for link:"
                                (str (or container service) ":" interface)))
            (cond-> cfg
              true      (assoc-in [:domains domain :status] nil)
              container (update-in [:containers container :links] conjv l)
              service   (update-in [:services service :links] conjv l)))
          {} (:links net-cfg)))

(defn link-add-outer-interface
  "outer-interface format:
     - standalone:  container          '-' interface
     - compose:     service '_' index  '-' interface
     - len > 15:    'c' cid[0:8]       '-' interface[0:5]"
  [{:as link :keys [container service interface]} cid index]
  (let [oif (str (if service (str service "_" index) container) "-" interface)
        oif (if (<= (count oif) 15)
              oif
              (str "c" (.substring cid 0 8)  "-" (.substring interface 0 5)))]
    (assoc link :outer-interface oif)))

(defn link-add-offset [{:as link :keys [ip mac]} offset]
  (let [mac (when mac
              (addrs/int->mac
                (+ offset (addrs/mac->int mac))))
        ip (when ip
             (let [[ip prefix] (S/split ip #"/")]
               (str (addrs/int->ip
                      (+ offset (addrs/ip->int ip)))
                    "/" prefix)))]
    (merge link (when mac {:mac mac}) (when ip {:ip ip}))))

;;; General commands

(defn run [cmd & [{:as opts :keys [quiet id]}]]
  (P/let [{:keys [info error]} @ctx
          id (if id (str " (" id ")") "")
          _ (when (not quiet) (info (str "Running" id ": " cmd)))
          res (P/catch (spawn cmd) #(identity %))]
    (P/do
      (when (not quiet)
        (if (= 0 (:code res))
          (when (not (empty? (:stdout res)))
            (info (str "Result" id ":\n"
                       (indent (:stdout res) "  "))))
          (error (str "[code: " (:code res) "]" id ":\n"
                      (indent (:stdout res) "  ") "\n"
                      (indent (:stderr res) "  ")))))
      res)))

(defn start-ovs []
  (P/let [res (run OVS-START-CMD)]
    (if (not= 0 (:code res))
      (fatal 1 (str "Failed starting OVS: " (:stderr res)))
      res)))

(defn kmod-loaded? [kmod]
  (P/let [cmd (str "grep -o '^" kmod "\\>' /proc/modules")
          res (run cmd {:quiet true})]
    (and (= 0 (:code res)) (= kmod (trim (:stdout res))))))

;;; Link and bridge commands

(defn check-no-domain [domain]
  (P/let [{:keys [info bridge-mode]} @ctx
          cmd (get {:ovs (str "ovs-vsctl list-ifaces " domain)
                    :linux (str "ip link show type bridge " domain)}
                   bridge-mode)
          res (run cmd {:quiet true})]
    (if (= 0 (:code res))
      ;; TODO: maybe mark as :exists and use without cleanup
      (fatal 1 (str "Domain " domain " already exists"))
      (if (re-seq #"(does not exist|no bridge named)" (:stderr res))
        true
        (fatal 1 (str "Unable to run '" cmd "': " (:stderr res)))))))

(defn domain-create [domain]
  (P/let [{:keys [info bridge-mode]} @ctx
          _ (info "Creating domain/switch" domain)
          cmd (get {:ovs (str "ovs-vsctl add-br " domain)
                    :linux (str "ip link add " domain " up type bridge")}
                   bridge-mode)
          res (run cmd)]
    (if (not= 0 (:code res))
      (fatal 1 (str "Unable to create bridge/domain " domain))
      (swap! ctx assoc-in [:network-state :domains domain :status] :created))))

(defn domain-del [domain]
  (P/let [{:keys [info error bridge-mode]} @ctx
          _ (info "Deleting domain/switch" domain)
          cmd (get {:ovs (str "ovs-vsctl del-br " domain)
                    :linux (str "ip link del " domain)} bridge-mode)
          res (run cmd)]
    (if (not= 0 (:code res))
      (error (str "Unable to delete bridge " domain))
      (swap! ctx assoc-in [:network-state :domains domain :status] nil))))

(defn domain-add-link [domain interface]
  (P/let [{:keys [error bridge-mode]} @ctx
          cmd (get {:ovs (str "ovs-vsctl add-port " domain " " interface)
                    :linux (str "ip link set dev " interface " master " domain)}
                   bridge-mode)
          res (run cmd)]
    (if (not= 0 (:code res))
      (error (str "ERROR: link " interface
                  " failed to add into " domain))
      res)))

(defn domain-drop-link [domain interface]
  (P/let [{:keys [error bridge-mode]} @ctx
          cmd (get {:ovs (str "ovs-vsctl del-port " domain " " interface)
                    :linux (str "ip link set dev " interface " nomaster")}
                   bridge-mode)
          res (run cmd)]
    (if (not= 0 (:code res))
      (error (str "ERROR: link " interface
                  " failed to drop from " domain))
      res)))


(defn link-create [link inner-pid outer-pid]
  (P/let [{:keys [error]} @ctx
          {:keys [interface mtu mac ip route outer-interface]} link
          status-path [:network-state :links outer-interface :status]
          link-status (get-in @ctx status-path)]
    (if link-status
      (error (str "Link " outer-interface " already exists"))
      (P/let [_ (swap! ctx assoc-in status-path :created)
              cmd (str "./veth-link.sh"
                       (when mac (str " --mac0 " mac))
                       (when ip (str " --ip0 " ip))
                       (when route (str " --route0 '" route "'"))
                       " --mtu " (or mtu 9000)
                       " " interface " " outer-interface
                       " " inner-pid " " outer-pid)]
        (run cmd {:id outer-interface})))))

(defn link-del [interface]
  (P/let [{:keys [error]} @ctx
          status-path [:network-state :links interface :status]
          res (run (str "ip link del " interface))]
    (if (not= 0 (:code res))
      (error (str "Could not delete " interface ": " (:stderr res)))
      (do (swap! ctx assoc-in status-path nil)
          res))))


;;; docker/docker-compose utilities

(defn get-container-id
  "Returns nil if no container ID can be determined (e.g. we are not
  running in a container)"
  []
  (P/let [[cgroup mountinfo]
          , (P/all [(read-file "/proc/self/cgroup" "utf8")
                    (read-file "/proc/self/mountinfo" "utf8")])
          ;; docker
          d-cgroups (map second (re-seq #"/docker/([^/\n]*)" cgroup))
          ;; podman (root)
          p-cgroups (map second (re-seq #"libpod-([^/.\n]*)" cgroup))
          ;; general fallback
          o-mounts (map second (re-seq #"workdir=.*/([^/]*)/work" mountinfo))]
    (first (concat d-cgroups p-cgroups o-mounts))))

(defn get-container
  [client cid]
  (P/-> ^obj (.inspect ^obj (.getContainer client cid)) ->clj))

(defn get-compose-labels
  "Return a map of compose related container labels with the
  'com.docker.compose.' prefix stripped off and converted to
  keywords."
  [container]
  (into {}
        (for [[k v] (get-in container [:Config :Labels])
              :let [n (name k)]
              :when (S/starts-with? n "com.docker.compose.")]
          [(keyword (-> n
                        (S/replace #"^com\.docker\.compose\." "")
                        (S/replace #"\." "-")))
           v])))

(defn list-containers
  [client & [filters]]
  (P/let [opts (if filters {:filters (json-str filters)} {})]
    ^obj (.listContainers client (->js opts))))

;;;

(defn docker-client [path]
  (P/let [{:keys [error log]} @ctx]
    (P/catch
      (P/let
        [client (Docker. #js {:socketPath path})
         ;; client is lazy so trigger it now
         containers (list-containers client)]
        (log (str "Listening on " path))
        client)
      #(error "Could not start docker client on '" path "': " %))))

(defn docker-listen [client filters event-callback]
  (P/let [{:keys [error log]} @ctx]
    (P/catch
      (P/let
        [ev-stream ^obj (.getEvents client #js {:filters (json-str filters)})
         _ ^obj (.on ev-stream "data"
                     #(event-callback client (->clj (js/JSON.parse %))))]
        ev-stream)
      #(error "Could not start docker listener"))))


(defn handle-event [client {:keys [status id]}]
  (P/let [{:keys [info log network-state compose-opts self-pid]} @ctx
          container (get-container client id)
          cname (->> container :Name (re-seq #"(.*/)?(.*)") first last)
          Pid (-> container :State :Pid)

          clabels (get-compose-labels container)
          svc-name (:service clabels)
          svc-num (:container-number clabels)
          cindex (if svc-num (js/parseInt svc-num) 1)
          svc-match? (and (let [p (:project compose-opts)]
                            (or (not p) (= p (:project clabels))))
                          (let [d (:project-working_dir compose-opts)]
                            (or (not d) (= d (:project-working_dir clabels)))))
          clinks (get-in network-state [:containers cname :links])
          slinks (when svc-match?
                   (get-in network-state [:services svc-name :links]))
          links (concat clinks slinks)]
    (if (not (seq links))
      (info (str "Event: no links defined for " cname ", ignoring"))
      (do
        (info "Event:" status cname id)
        (P/all (for [{:as link :keys [interface domain ip mac]} links
                     :let [link (-> link
                                    (link-add-outer-interface id cindex)
                                    (link-add-offset (dec cindex)))
                           oif (:outer-interface link)]]
                 (if (= status "start")
                   (P/do
                     (log (str "Creating link (in " domain ") "
                               oif " -> " cname ":" interface))
                     (link-create link Pid self-pid)
                     (domain-add-link domain oif))
                   (P/do
                     (log (str "Deleting link (in " domain ") "
                               oif " -> " cname ":" interface))
                     (domain-drop-link domain oif)
                     (link-del oif)))))))))

(defn exit-handler [err origin]
  (let [{:keys [log info network-state]} @ctx
        {:keys [links domains containers]} network-state
        ;; filter for :created status (ignore :exists)
        intf-names (keys (filter #(= :created (-> % val :status)) links))
        domain-names (keys (filter #(= :created (-> % val :status)) domains))]
    (info (str "Got " origin ":") err)
    (P/do
      (when (seq intf-names)
        (P/do
          (log (str "Removing links: " (S/join ", " intf-names)))
          (P/all (for [interface intf-names]
                   (link-del interface)))))
      (when (seq domain-names)
        (P/do
          (log (str "Removing domains/switches: " (S/join ", " domain-names)))
          (P/all (for [domain domain-names]
                   (domain-del domain)))))
      (js/process.exit 127))))


;;;

(defn arg-checks [{:keys [network-file compose-file]}]
  (when (and (empty? network-file) (empty? compose-file))
    (fatal 2 "either --network-file or --compose-file is required")))

(defn state-checks [ctx-data]
  (P/let
    [{:keys [bridge-mode docker podman]} ctx-data
     kmod-okay? (if (= :ovs bridge-mode)
                  (kmod-loaded? "openvswitch")
                  true)]
    (when (not kmod-okay?)
      (fatal 2 "bridge-mode is 'ovs', but no 'openvswitch' module loaded"))
    (when (and (not docker) (not podman))
      (fatal 1 "Failed to start either docker or podman client/listener"))))

(defn main [& args]
  (P/let
    [{:as opts :keys [verbose]} (parse-opts usage args)
     {:keys [log info]} (swap! ctx merge (when verbose {:info Eprintln}))
     opts (merge
            opts
            {:bridge-mode (keyword (:bridge-mode opts))
             :network-file (mapcat #(S/split % #":") (:network-file opts))
             :compose-file (mapcat #(S/split % #":") (:compose-file opts))})
     _ (arg-checks opts)
     _ (when verbose (Eprintln "User options:") (Epprint opts))

     {:keys [network-file compose-file compose-project bridge-mode]} opts
     env (js->clj (js/Object.assign #js {} js/process.env))
     net-cfg (P/-> (load-configs compose-file network-file)
                   (interpolate-walk env)
                   (add-network-defaults))
     net-state (gen-network-state net-cfg)
     docker (docker-client (:docker-socket opts))
     podman (docker-client (:podman-socket opts))
     self-cid (get-container-id)
     self-container (when self-cid
                      (get-container docker self-cid))
     compose-opts (if compose-project
                    {:project compose-project}
                    (get-compose-labels self-container))
     ctx-data {:bridge-mode bridge-mode
               :network-state net-state
               :compose-opts compose-opts
               :docker docker
               :podman podman
               :self-pid js/process.pid
               :self-cid self-cid
               :self-container self-container}]

    (state-checks ctx-data)

    (swap! ctx merge ctx-data)

    (js/process.on "SIGINT" #(exit-handler % "signal"))
    (js/process.on "SIGTERM" #(exit-handler % "signal"))
    (js/process.on "uncaughtException" #(exit-handler %1 %2))

    (log "Bridge mode:" (name bridge-mode))
    (when verbose
      (info "Starting network state:")
      (Epprint net-state))
    (when self-cid
      (info "Detected enclosing container:" self-cid))
    (when compose-project
      (info "Detected compose context:" compose-project))

    (P/do
      (when (= :ovs bridge-mode)
        (start-ovs))

      ;; Check that domains/switches do not already exist
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (check-no-domain domain)))
      ;; Create domains/switch configs
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (domain-create domain)))

      (P/all (for [client [docker podman] :when client]
               (P/let
                 [event-filter {"event" ["start" "die"]}
                  ;; Listen for docker and/or podman events
                  _ (docker-listen client event-filter handle-event)
                  containers ^obj (list-containers client)]
                 ;; Generate fake events for existing containers
                 (P/all (for [container containers
                              :let [ev {:status "start"
                                        :from "pre-existing"
                                        :id (.-Id ^obj container)}]]
                          (handle-event client ev))))))
      #_(Epprint (dissoc @ctx :error :warn :log :info
                       :network-container :docker :podman :self-container))
      nil)))
