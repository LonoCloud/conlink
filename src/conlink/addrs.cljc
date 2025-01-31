;; Copyright (c) 2023, Viasat, Inc
;; Licensed under MPL 2.0

(ns conlink.addrs
  (:require [clojure.string :as string]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Network Address functions
;; - based on github.com/Viasat/clj-protocol

(defn num->string [n base]
  #?(:cljs  (.toString n base)
     :clj   (Long/toString n base)))

(defn string->num
  ([s] (string->num s 10))
  ([s base] #?(:cljs  (js/parseInt s base)
               :clj   (Long/parseLong s base))))

(defn octet->int
  "Convert sequence of octets/bytes `octets` (in MSB first order) into
  an integer"
  [octets]
  (reduce (fn [a o] (+ o (* a 256))) octets))

(defn int->octet
  "Convert integer `n` into `cnt` octets/bytes (in MSB first order)"
  [n cnt]
  (vec (first
         (reduce (fn [[res x] _] [(conj res (bit-and x 255)) (quot x 256)])
                 [(list) n]
                 (range cnt)))))

(defn int->hex
  "Convert integer `i` into hex string representation"
  [i]
  (let [h (num->string i 16)]
    (if (= 1 (count h)) (str "0" h) h)))

(defn ip->octet "Convert IPv4 string to bytes/octets" [ip]
  (map string->num (string/split ip #"[.]")))

(defn octet->ip "convert bytes/octets to IPv4 string" [os]
  (string/join "." os))

(defn mac->octet "Convert MAC addr string to bytes/octets" [mac]
  (map #(string->num %1 16) (string/split mac #":")))

(defn octet->mac "Convert bytes/octets to MAC addr string" [os]
  (string/join ":" (map int->hex os)))


(defn ip->int "Convert IPv4 string to uint32 value" [ip]
  (octet->int (ip->octet ip)))

(defn int->ip "Convert IPv4 uint32 value to IPv4 string" [num]
  (octet->ip (int->octet num 4)))

(defn mac->int "Convert MAC string to int value" [ip]
  (octet->int (mac->octet ip)))

(defn int->mac "Convert MAC int value to IPv4 string" [num]
  (octet->mac (int->octet num 6)))



