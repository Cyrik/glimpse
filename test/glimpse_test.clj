(ns glimpse-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimpse :as glimpse]
            [glimpse.datomic :as glimpse.datomic]
            [matcher-combinators.test :refer [match?]]))

(deftest tracks-direct-lookups
  (testing "given a tracked map with present, nil, missing, and nested keys"
    (let [tracked (glimpse/track {:user/name "Alice"
                                  :user/email nil
                                  :user/address {:address/city "Berlin"}})]
      (testing "when code reads those keys"
        (let [read-values {:name (:user/name tracked)
                           :email (:user/email tracked)
                           :missing (get tracked :user/missing ::not-found)
                           :city (-> tracked :user/address :address/city)}]
          (testing "then Glimpse distinguishes accessed, nil, and missing paths"
            (is (match? {:name "Alice"
                         :email nil
                         :missing ::not-found
                         :city "Berlin"}
                        read-values))
            (is (match? {[:user/name] 1
                         [:user/email] 1
                         [:user/missing] 1
                         [:user/address] 1
                         [:user/address :address/city] 1}
                        (glimpse/access-counts tracked)))
            (is (match? #{[:user/email]} (glimpse/nil-paths tracked)))
            (is (match? #{[:user/missing]} (glimpse/missing-paths tracked)))
            (is (match? #{[:user/name]
                          [:user/email]
                          [:user/missing]
                          [:user/address]
                          [:user/address :address/city]}
                        (:accessed-paths (glimpse/summarize tracked))))))))))

(deftest contains-is-not-tracked
  (testing "given a tracked map"
    (let [tracked (glimpse/track {:user/name "Alice"})]
      (testing "when code checks key presence with contains?"
        (let [present? (contains? tracked :user/name)]
          (testing "then the presence check is not counted as a tracked access"
            (is (match? true present?))
            (is (match? {} (glimpse/access-counts tracked)))))))))

(deftest find-tracks-and-wraps-values
  (testing "given a tracked map with a nested map value"
    (let [tracked (glimpse/track {:user/address {:address/city "Berlin"}})]
      (testing "when code finds a present entry and a missing entry"
        (let [entry (find tracked :user/address)
              missing-entry (find tracked :user/missing)
              city (:address/city (val entry))]
          (testing "then find records lookups and wraps the present value"
            (is (match? :user/address (key entry)))
            (is (match? "Berlin" city))
            (is (match? nil missing-entry))
            (is (match? {[:user/address] 1
                         [:user/address :address/city] 1
                         [:user/missing] 1}
                        (glimpse/access-counts tracked)))
            (is (match? #{[:user/missing]} (glimpse/missing-paths tracked)))))))))

(deftest iteration-tracks-realized-entries
  (testing "given a tracked map with a nested map value"
    (let [tracked (glimpse/track {:user/name "Alice"
                                  :user/address {:address/city "Berlin"}})]
      (testing "when code realizes map iteration"
        (let [rendered (into {}
                             (map (fn [[k v]]
                                    [k (if (map? v) (:address/city v) v)]))
                             tracked)]
          (testing "then realized entries are counted and nested values remain tracked"
            (is (match? {:user/name "Alice"
                         :user/address "Berlin"}
                        rendered))
            (is (match? {[:user/name] 1
                         [:user/address] 1
                         [:user/address :address/city] 1}
                        (glimpse/access-counts tracked)))))))))

(deftest tracked-maps-can-be-used-as-transients
  (testing "given a tracked map"
    (let [tracked (glimpse/track {:user/name "Alice"
                                  :user/email nil})]
      (testing "when code creates a transient from it"
        (let [updated (persistent!
                       (dissoc!
                        (assoc! (transient tracked) :user/role "admin")
                        :user/email))]
          (testing "then transient map operations work without recording reads"
            (is (match? {:user/name "Alice"
                         :user/role "admin"}
                        updated))
            (is (match? {} (glimpse/access-counts tracked)))))))))

(deftest java-map-interop-respects-tracking-and-contract
  (testing "given a tracked map and an equivalent plain map"
    (let [tracked (glimpse/track {:a 1 :c {:d 3}})
          plain {:a 1 :c {:d 3}}]
      (testing "when reading via java.util.Map.get"
        (testing "then the access is recorded and nested values are wrapped"
          (let [v (.get ^java.util.Map tracked :a)
                nested (.get ^java.util.Map tracked :c)]
            (is (match? 1 v))
            (is (match? true (instance? clojure.lang.ILookup nested)))
            (is (match? #{[:a] [:c]}
                        (set (keys (glimpse/access-counts tracked))))))))
      (testing "when invoking Object.equals and Object.hashCode"
        (testing "then they match the underlying map's Java contract"
          (is (match? true (.equals ^Object tracked plain)))
          (is (match? true (.equals ^Object plain tracked)))
          (is (match? (.hashCode ^Object plain) (.hashCode ^Object tracked))))))))

(deftest behaves-like-a-regular-map
  (testing "given a tracked map and an equivalent plain map"
    (let [tracked (glimpse/track {:a 1 :b 2 :c {:d 3}})
          plain {:a 1 :b 2 :c {:d 3}}]
      (testing "when checking Clojure equality and hash"
        (testing "then they're equal in both directions and hash the same"
          (is (match? plain tracked))
          (is (match? tracked plain))
          (is (match? (hash plain) (hash tracked)))))
      (testing "when reading via get and get-in"
        (testing "then values match the plain-map shape"
          (is (match? 1 (get tracked :a)))
          (is (match? 3 (get-in tracked [:c :d])))))
      (testing "when applying merge, update, and select-keys"
        (testing "then results match the plain-map outcomes"
          (is (match? (assoc plain :e 4) (merge tracked {:e 4})))
          (is (match? (update plain :a inc) (update tracked :a inc)))
          (is (match? {:a 1 :b 2} (select-keys tracked [:a :b])))))
      (testing "when traversing with reduce-kv"
        (testing "then every entry is visited"
          (is (match? (set (seq plain))
                      (set (reduce-kv (fn [acc k v] (conj acc [k v])) [] tracked)))))))))

(deftest lazy-sequences-track-realized-items
  (testing "given a tracked map containing a lazy sequence of maps"
    (let [tracked (glimpse/track {:users (lazy-seq
                                          [{:user/name "Alice"}
                                           {:user/name "Bob"}])})]
      (testing "when code reads the lazy sequence value"
        (let [users (:users tracked)]
          (testing "then wrapping preserves the lazy sequence without realizing it"
            (is (match? true (instance? clojure.lang.LazySeq users)))
            (is (match? false (realized? users))))
          (testing "when code realizes only the first item"
            (let [first-name (-> users first :user/name)]
              (testing "then only the realized item contributes nested accesses"
                (is (match? "Alice" first-name))
                (is (match? {[:users] 1
                             [:users :user/name] 1}
                            (glimpse/access-counts tracked)))))))))))

(deftest realized-sequences-track-without-changing-type
  (testing "given a tracked map containing a vector of maps"
    (let [tracked (glimpse/track {:users [{:user/name "Alice"}
                                          {:user/name "Bob"}]})]
      (testing "when code reads the vector value"
        (let [users (:users tracked)]
          (testing "then wrapping preserves the vector shape"
            (is (match? true (vector? users))))
          (testing "when code reads an item"
            (let [first-name (-> users first :user/name)]
              (testing "then the nested item access is tracked"
                (is (match? "Alice" first-name))
                (is (match? {[:users] 1
                             [:users :user/name] 1}
                            (glimpse/access-counts tracked)))))))))))

(deftest track-call-summarizes-a-function-call
  (testing "given a function that accepts data as its final argument"
    (testing "when track-call invokes it with a tracked map"
      (let [analysis (glimpse/track-call
                      (fn [prefix tracked-user]
                        (str prefix (:user/name tracked-user)))
                      {:user/name "Alice"}
                      :args ["User: "])]
        (testing "then the return value and access summary are reported together"
          (is (match? {:accessed-paths #{[:user/name]}
                       :nil-paths #{}
                       :missing-paths #{}
                       :counts {:access {[:user/name] 1}
                                :nil {}
                                :missing {}}
                       :result "User: Alice"}
                      analysis)))))))

(defn load-users []
  [{:user/name "Alice" :user/email nil}
   {:user/name "Bob"}])

(deftest with-instrumented-var-root-summarizes-returned-maps
  (testing "given a var that returns maps"
    (testing "when its root is temporarily instrumented"
      (let [analysis (glimpse/with-instrumented-var-root
                      #'load-users
                      #(doseq [user (load-users)]
                         (:user/name user)
                         (:user/email user)))
            paths-by-path (into {} (map (juxt :path identity)) (:paths analysis))]
        (testing "then access analysis is aggregated across returned maps"
          (is (match? 2 (:item-count analysis)))
          (is (match? {:path [:user/name]
                       :accesses 2
                       :nils 0
                       :missing 0
                       :nil-rate "0%"
                       :missing-rate "0%"}
                      (get paths-by-path [:user/name])))
          (is (match? {:path [:user/email]
                       :accesses 2
                       :nils 1
                       :missing 1
                       :nil-rate "50%"
                       :missing-rate "50%"}
                      (get paths-by-path [:user/email]))))))))

(deftest datomic-pull-pattern-comparison
  (testing "given a Datomic pull pattern"
    (let [pulled-paths (glimpse.datomic/pull-pattern->paths
                        [:user/name
                         :user/email
                         {:user/address [:address/city]}])]
      (testing "when it is compared with accessed paths"
        (let [comparison (glimpse.datomic/compare-to-pattern
                          #{[:user/name]
                            [:user/phone]}
                          pulled-paths)]
          (testing "then fetched and accessed paths are classified"
            (is (match? #{[:user/name]
                          [:user/email]
                          [:user/address :address/city]}
                        pulled-paths))
            (is (match? {:accessed-paths #{[:user/name]
                                           [:user/phone]}
                         :over-fetched #{[:user/email]
                                         [:user/address :address/city]}
                         :under-fetched #{[:user/phone]}}
                        comparison))))))))
