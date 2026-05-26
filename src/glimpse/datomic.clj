(ns glimpse.datomic
  "Helpers for comparing Datomic pull patterns with Glimpse access paths."
  (:require [clojure.set :as set]))

(defn compare-to-pattern
  "Compare accessed paths against paths that were fetched.

  Returns:
    {:accessed-paths #{...}
     :over-fetched #{...}
     :under-fetched #{...}}

  `over-fetched` contains pulled paths that were never read.
  `under-fetched` contains read paths that were not in the pulled path set."
  [accessed-paths pulled-paths]
  (let [accessed-set (set accessed-paths)
        pulled-set (set pulled-paths)]
    {:accessed-paths accessed-set
     :over-fetched (set/difference pulled-set accessed-set)
     :under-fetched (set/difference accessed-set pulled-set)}))

(defn pull-pattern->paths
  "Extract leaf paths from a Datomic pull pattern.

  This intentionally handles the common pull-pattern shapes Glimpse needs for
  access comparison. It is not a full Datomic pull parser."
  ([pattern] (pull-pattern->paths pattern []))
  ([pattern prefix]
   (reduce
    (fn [paths item]
      (cond
        (keyword? item)
        (conj paths (conj prefix item))

        (map? item)
        (reduce-kv
         (fn [ps k v]
           (let [new-prefix (conj prefix (if (vector? k) (second k) k))]
             (if (vector? v)
               (into ps (pull-pattern->paths v new-prefix))
               (conj ps new-prefix))))
         paths
         item)

        :else paths))
    #{}
    pattern)))

(comment
  (def pulled-paths
    (pull-pattern->paths
     [:user/name
      :user/email
      {:user/address [:address/city]}]))

  (compare-to-pattern
   #{[:user/name] [:user/address] [:user/address :address/city]}
   pulled-paths))
