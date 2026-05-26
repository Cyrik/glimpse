(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.cyrik/glimpse)

(defn- git-tag-version
  "Returns the semver from a tag pointing exactly at HEAD, or nil.
   Only considers tags shaped like X.Y.Z or vX.Y.Z (with optional -suffix)."
  []
  (let [out (try
              (b/git-process {:git-args ["tag" "--points-at" "HEAD"]})
              (catch Exception _ ""))]
    (->> (str/split-lines (or out ""))
         (map str/trim)
         (keep #(some-> (re-matches #"v?(\d+\.\d+\.\d+(?:-[\w.]+)?)" %) second))
         first)))

(def version (or (System/getenv "VERSION") (git-tag-version) "0.0.0-SNAPSHOT"))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def ^:private pom-template
  [[:description "Track which map paths your Clojure code actually reads."]
   [:url "https://github.com/Cyrik/glimpse"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:developers
    [:developer
     [:name "Lukas Domagala"]]]
   [:scm
    [:url "https://github.com/Cyrik/glimpse"]
    [:connection "scm:git:git://github.com/Cyrik/glimpse.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Cyrik/glimpse.git"]
    [:tag (str "v" version)]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data pom-template})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Wrote" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  (println "Installed" lib version "to ~/.m2"))

(defn deploy [_]
  (when (and (str/includes? version "SNAPSHOT")
             (not (System/getenv "ALLOW_SNAPSHOT")))
    (throw (ex-info (str "Refusing to deploy SNAPSHOT version: " version
                         "\nTag a release (bb release X.Y.Z) or set ALLOW_SNAPSHOT=1 to override.")
                    {:version version})))
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})
              :sign-releases? false})
  (println "Deployed" lib version "to Clojars"))
