(ns glimpse
  "Track map access to understand which data your code actually reads.

  Wrap a map with `track`, pass it to ordinary Clojure code, then inspect the
  recorded lookup paths:

    (let [tracked (track {:user/name \"Alice\"
                          :user/email nil})]
      (:user/name tracked)
      (:user/missing tracked)
      (summarize tracked))

  Glimpse distinguishes present nil values from missing keys, tracks nested map
  access, and can optionally record caller locations."
  (:require [clojure.string :as str]))

(defprotocol ITrackedAccess
  (access-counts [this] "Returns map of path -> access count")
  (nil-counts [this] "Returns map of path -> present nil access count")
  (missing-counts [this] "Returns map of path -> missing key access count")
  (access-locations [this] "Returns map of path -> set of caller locations"))

(declare track)

(def ^:private self-class-prefix
  (clojure.lang.Compiler/munge (str (ns-name *ns*))))

(defn- extract-caller-location
  "Extract the first 'interesting' caller from a stack trace.
   Filters out Glimpse internals and Clojure/Java core."
  []
  (let [frames (.getStackTrace (Thread/currentThread))
        interesting? (fn [^StackTraceElement frame]
                       (let [class-name (.getClassName frame)]
                         (and (not (str/starts-with? class-name self-class-prefix))
                              (not (str/starts-with? class-name "clojure.lang."))
                              (not (str/starts-with? class-name "clojure.core"))
                              (not (str/starts-with? class-name "java.lang.Thread")))))]
    (when-let [^StackTraceElement frame (first (filter interesting? frames))]
      {:class (.getClassName frame)
       :method (.getMethodName frame)
       :file (.getFileName frame)
       :line (.getLineNumber frame)})))

(declare track*)

(defn- track-sequential
  [xs accessed-atom nil-atom missing-atom locations-atom path]
  (let [wrap-item (fn [item]
                    (if (map? item)
                      (track* item accessed-atom nil-atom missing-atom locations-atom path)
                      item))]
    (if (vector? xs)
      (mapv wrap-item xs)
      (map wrap-item xs))))

(defn- track-value
  "Wrap a value for tracking. Maps and collections of maps get wrapped."
  [v accessed-atom nil-atom missing-atom locations-atom path]
  (cond
    (map? v) (track* v accessed-atom nil-atom missing-atom locations-atom path)
    (sequential? v) (track-sequential v accessed-atom nil-atom missing-atom locations-atom path)
    :else v))

(defn- tracked-entry
  [k v accessed-atom nil-atom missing-atom locations-atom path]
  (clojure.lang.MapEntry/create
   k
   (track-value v accessed-atom nil-atom missing-atom locations-atom path)))

(defn- record-lookup!
  [data accessed-atom nil-atom missing-atom locations-atom path k]
  (let [full-path (conj path k)
        present? (contains? data k)
        v (get data k)]
    (swap! accessed-atom update full-path (fnil inc 0))
    (if present?
      (when (nil? v)
        (swap! nil-atom update full-path (fnil inc 0)))
      (swap! missing-atom update full-path (fnil inc 0)))
    (when locations-atom
      (when-let [loc (extract-caller-location)]
        (swap! locations-atom update-in [full-path loc] (fnil inc 0))))
    {:path full-path
     :present? present?
     :value v}))

(defn track
  "Wrap a map to record all key accesses. Nested maps are also wrapped.

  Returns a map-like object that records every key access with counts.
  Call (access-counts wrapped-map) to get map of path -> access count.
  Call (nil-counts wrapped-map) to get map of path -> present nil access count.
  Call (missing-counts wrapped-map) to get map of path -> missing key access count.
  Call (access-locations wrapped-map) to get map of path -> set of caller locations.

  Options:
    :with-locations? - if true, capture stack traces for each access (expensive)

  Lookup, `find`, and realized map iteration are tracked. `contains?` is treated
  as a presence check and is not tracked.

  Lazy sequences are tracked as they are realized. Force realization with `doall`
  or `vec` if you want diagnostics for every item in a lazy result.

  Tracked maps implement enough `java.util.Map` behavior for compatibility.
  `java.util.Map.get` is tracked, but Java collection views such as `entrySet`,
  `keySet`, and `values` should not be used when you need diagnostics.

  Example:
    (let [t (track {:user/name \"Alice\"
                    :user/email nil
                    :user/address {:city \"Berlin\"}})]
      (:user/name t)
      (:user/name t)  ;; accessed twice
      (:user/email t)  ;; present but nil
      (:user/missing t)  ;; missing, returns nil
      {:access (access-counts t)
       :nil (nil-counts t)
       :missing (missing-counts t)})

  With location tracking:
    (let [t (track data :with-locations? true)]
      (:user/name t)
      (access-locations t))  ;; => {[:user/name] #{{:class \"...\" :file \"...\" :line 42}}}"
  ([data] (track* data (atom {}) (atom {}) (atom {}) nil []))
  ([data & {:keys [with-locations?]}]
   (track* data (atom {}) (atom {}) (atom {}) (when with-locations? (atom {})) [])))

(defn- track*
  "Internal implementation of track with explicit atoms."
  [data accessed-atom nil-atom missing-atom locations-atom path]
  (reify
    clojure.lang.ILookup
    (valAt [_ k]
      (let [{:keys [path value]} (record-lookup! data accessed-atom nil-atom missing-atom locations-atom path k)]
        (track-value value accessed-atom nil-atom missing-atom locations-atom path)))
    (valAt [_ k not-found]
      (let [{:keys [path present? value]} (record-lookup! data accessed-atom nil-atom missing-atom locations-atom path k)]
        (if present?
          (track-value value accessed-atom nil-atom missing-atom locations-atom path)
          not-found)))

    clojure.lang.IFn
    (invoke [this k] (.valAt this k))
    (invoke [this k not-found] (.valAt this k not-found))

    clojure.lang.Seqable
    (seq [_]
      (seq (map (fn [k]
                  (let [{:keys [path value]} (record-lookup! data accessed-atom nil-atom missing-atom locations-atom path k)]
                    (tracked-entry k value accessed-atom nil-atom missing-atom locations-atom path)))
                (keys data))))

    java.lang.Iterable
    (iterator [this]
      (.iterator (seq this)))

    clojure.lang.Counted
    (count [_] (count data))

    clojure.lang.Associative
    (containsKey [_ k] (contains? data k))
    (entryAt [_ k]
      (let [{:keys [path present? value]} (record-lookup! data accessed-atom nil-atom missing-atom locations-atom path k)]
        (when present?
          (tracked-entry k value accessed-atom nil-atom missing-atom locations-atom path))))
    (assoc [_ k v] (track* (assoc data k v) accessed-atom nil-atom missing-atom locations-atom path))

    clojure.lang.IPersistentCollection
    (cons [_ o] (track* (conj data o) accessed-atom nil-atom missing-atom locations-atom path))
    (empty [_] (track* (empty data) accessed-atom nil-atom missing-atom locations-atom path))
    (equiv [_ o] (= data o))

    clojure.lang.IPersistentMap
    (assocEx [_ k v] (track* (.assocEx ^clojure.lang.IPersistentMap data k v) accessed-atom nil-atom missing-atom locations-atom path))
    (without [_ k] (track* (dissoc data k) accessed-atom nil-atom missing-atom locations-atom path))

    clojure.lang.IEditableCollection
    (asTransient [_] (transient data))

    clojure.lang.MapEquivalence

    clojure.lang.IHashEq
    (hasheq [_] (hash data))

    java.util.Map
    (size [_] (count data))
    (isEmpty [_] (zero? (count data)))
    (containsValue [_ v] (.containsValue ^java.util.Map data v))
    (get [this k] (.valAt ^clojure.lang.ILookup this k))
    (keySet [_] (.keySet ^java.util.Map data))
    (values [_] (.values ^java.util.Map data))
    (entrySet [_] (.entrySet ^java.util.Map data))
    (put [_ _ _] (throw (UnsupportedOperationException.)))
    (remove [_ _] (throw (UnsupportedOperationException.)))
    (putAll [_ _] (throw (UnsupportedOperationException.)))
    (clear [_] (throw (UnsupportedOperationException.)))

    ITrackedAccess
    (access-counts [_] @accessed-atom)
    (nil-counts [_] @nil-atom)
    (missing-counts [_] @missing-atom)
    (access-locations [_] (when locations-atom @locations-atom))

    Object
    (equals [_ o] (.equals ^Object data o))
    (hashCode [_] (.hashCode ^Object data))
    (toString [_] (str data))))

;; Convenience functions
(defn accessed-paths
  "Returns the set of accessed paths."
  [tracked-map]
  (set (keys (access-counts tracked-map))))

(defn nil-paths
  "Returns set of paths where a present key returned nil."
  [tracked-map]
  (set (keys (nil-counts tracked-map))))

(defn missing-paths
  "Returns set of paths where an absent key was accessed."
  [tracked-map]
  (set (keys (missing-counts tracked-map))))

(defn format-location
  "Format a location map as a readable string like 'profile_view.clj:42 (render-user)'."
  [{:keys [file line method]}]
  (str file ":" line " (" method ")"))

(defn summarize
  "Summarize tracking results from a tracked map.
  Returns {:accessed-paths #{...} :nil-paths #{...} :missing-paths #{...} :counts {...} :locations {...}}

  - accessed-paths: set of all paths the tracked code tried to access
  - nil-paths: set of paths where a present key returned nil
  - missing-paths: set of paths where an absent key was accessed
  - counts: detailed counts for analysis
  - locations: map of path -> {location -> count} (if :with-locations? was true)"
  [tracked-map]
  (let [acc (access-counts tracked-map)
        nils (nil-counts tracked-map)
        missing (missing-counts tracked-map)
        locs (access-locations tracked-map)]
    (cond-> {:accessed-paths (set (keys acc))
             :nil-paths (set (keys nils))
             :missing-paths (set (keys missing))
             :counts {:access acc :nil nils :missing missing}}
      locs (assoc :locations locs))))

(defn paths-with-locations
  "Returns a map of paths to their formatted caller locations with counts.
   Useful for investigating where specific accesses come from.

   Example output:
     {[:user/name] {\"profile_view.clj:42 (render-user)\" 10}
      [:user/missing] {\"profile_view.clj:57 (render-user)\" 5}}

   Only works if tracking was done with :with-locations? true."
  [tracked-map]
  (when-let [locs (access-locations tracked-map)]
    (into {} (map (fn [[path loc-counts]]
                    [path (into {} (map (fn [[loc cnt]]
                                          [(format-location loc) cnt]))
                                loc-counts)]))
          locs)))

(defn top-level-keys
  "Extract just the top-level keys from accessed paths.
  Useful for a quick overview of which keys were touched."
  [accessed-paths]
  (into #{} (map first) accessed-paths))

(defn track-call
  "Call f with tracked data as its final argument and return the access summary.

  Options:
    :args - arguments passed to f before the tracked data
    :realize - optional function used to force f's result before summarizing

  Returns the same keys as `summarize`, plus:
    :result - the return value from f"
  [f data & {:keys [args realize] :or {args []}}]
  (let [tracked (track data)
        result (apply f (concat args [tracked]))]
    (when realize
      (realize result))
    (assoc (summarize tracked) :result result)))

(defn- track-result
  [result tracked-atom with-locations?]
  (let [wrap-one (fn [item]
                   (if (map? item)
                     (let [tracked (track item :with-locations? with-locations?)]
                       (swap! tracked-atom conj tracked)
                       tracked)
                     item))]
    (cond
      (map? result) (wrap-one result)
      (vector? result) (mapv wrap-one result)
      (sequential? result) (map wrap-one result)
      :else result)))

(defn- merge-counts
  [count-maps]
  (reduce (fn [acc m]
            (reduce-kv (fn [a k v] (update a k (fnil + 0) v)) acc m))
          {}
          count-maps))

(defn- merge-locations
  [location-maps]
  (reduce (fn [acc m]
            (reduce-kv
             (fn [a path loc-counts]
               (reduce-kv
                (fn [a2 loc cnt]
                  (update-in a2 [path loc] (fnil + 0) cnt))
                a
                loc-counts))
             acc
             m))
          {}
          (remove nil? location-maps)))

(defn- descending-rate-sort-value
  [rate]
  (- (parse-double (subs rate 0 (dec (count rate))))))

(defn with-instrumented-var-root
  "Temporarily replace var-to-instrument's root and run f.

  The var must contain a function. During f, maps returned by that function are
  wrapped with `track`. After f returns, the original var root is restored and
  aggregate access analysis is returned.

  This mutates the var root with `alter-var-root` while f runs. It is intended
  for REPL/dev diagnostics, not concurrent production request handling.

  Options:
    :with-locations? - capture caller stack traces for each access

  Lazy sequence results are tracked only as far as f realizes them. Force with
  `doall` or `vec` if you want diagnostics for every item."
  [var-to-instrument f & {:keys [with-locations?]}]
  (let [tracked-items (atom [])
        original-fn @var-to-instrument]
    (try
      (alter-var-root var-to-instrument
                      (fn [_]
                        (fn [& args]
                          (track-result (apply original-fn args)
                                        tracked-items
                                        with-locations?))))
      (f)
      (let [items @tracked-items
            all-access-counts (merge-counts (map access-counts items))
            all-nil-counts (merge-counts (map nil-counts items))
            all-missing-counts (merge-counts (map missing-counts items))
            all-locations (when with-locations?
                            (merge-locations (map access-locations items)))
            path-analysis (for [path (keys all-access-counts)
                                :let [accesses (get all-access-counts path 0)
                                      nils (get all-nil-counts path 0)
                                      missing (get all-missing-counts path 0)
                                      nil-rate (if (pos? accesses)
                                                 (* 100.0 (/ nils accesses))
                                                 0)
                                      missing-rate (if (pos? accesses)
                                                     (* 100.0 (/ missing accesses))
                                                     0)
                                      locs (get all-locations path)]]
                            (cond-> {:path path
                                     :accesses accesses
                                     :nils nils
                                     :missing missing
                                     :nil-rate (format "%.0f%%" nil-rate)
                                     :missing-rate (format "%.0f%%" missing-rate)}
                              locs (assoc :locations (into {} (map (fn [[loc cnt]]
                                                                     [(format-location loc) cnt]))
                                                           locs))))]
        {:item-count (count items)
         :paths (vec (sort-by :path path-analysis))
         :by-nil-rate (vec (sort-by (comp descending-rate-sort-value :nil-rate) path-analysis))
         :by-missing-rate (vec (sort-by (comp descending-rate-sort-value :missing-rate) path-analysis))})
      (finally
        (alter-var-root var-to-instrument (constantly original-fn))))))

(comment
  ;; Basic lookups and summary
  (let [tracked (track {:user/name "Alice"
                        :user/email nil
                        :user/address {:address/city "Berlin"}})]
    (:user/name tracked)
    (:user/email tracked)
    (:user/missing tracked)
    (-> tracked :user/address :address/city)
    (summarize tracked))

  ;; Focused path helpers
  (let [tracked (track {:user/name "Alice"
                        :user/email nil})]
    (:user/name tracked)
    (:user/email tracked)
    (:user/missing tracked)
    {:accessed-paths (accessed-paths tracked)
     :nil-paths (nil-paths tracked)
     :missing-paths (missing-paths tracked)
     :top-level (top-level-keys (accessed-paths tracked))})

  ;; `get` with a not-found value still records the missing path.
  (let [tracked (track {:user/name "Alice"})]
    {:value (get tracked :user/missing ::not-found)
     :summary (summarize tracked)})

  ;; `find` records the lookup and returns a tracked value.
  (let [tracked (track {:user/address {:address/city "Berlin"}})
        entry (find tracked :user/address)]
    (:address/city (val entry))
    (summarize tracked))

  ;; Realized map iteration records each top-level entry and wraps map values.
  (let [tracked (track {:user/name "Alice"
                        :user/address {:address/city "Berlin"}})
        rendered (into {}
                       (map (fn [[k v]]
                              [k (if (map? v) (:address/city v) v)]))
                       tracked)]
    {:rendered rendered
     :summary (summarize tracked)})

  ;; Lazy sequences are tracked only as far as the consumer realizes them.
  (let [tracked (track {:users (map identity
                                    [{:user/name "Alice"}
                                     {:user/name "Bob"}])})]
    (-> tracked :users first :user/name)
    (summarize tracked))

  ;; Force lazy results when you want the full access picture.
  (track-call
   (fn [tracked-data] (map :user/name (:users tracked-data)))
   {:users [{:user/name "Alice"} {:user/name "Bob"}]}
   :realize doall)

  ;; Pass extra arguments before the tracked data.
  (track-call
   (fn [prefix tracked-user]
     (str prefix (:user/name tracked-user)))
   {:user/name "Alice"}
   :args ["User: "])

  ;; Temporarily instrument a var during a REPL/dev diagnostic run.
  (defn example-load-users []
    [{:user/name "Alice" :user/email nil}
     {:user/name "Bob"}])

  (let [load-users-var (resolve 'example-load-users)]
    (with-instrumented-var-root
     load-users-var
     #(doseq [user (@load-users-var)]
        (:user/name user)
        (:user/email user))))

  (ns-unmap *ns* 'example-load-users)

  ;; Capture caller locations when you need to know where reads happened.
  (let [tracked (track {:user/name "Alice"} :with-locations? true)]
    (:user/name tracked)
    (paths-with-locations tracked)))
