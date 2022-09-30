(defproject puppetlabs/dujour-version-check "1.0.0"
  :description "Dujour Version Check library"

  :parent-project {:coords [puppetlabs/clj-parent "5.2.6"]
                   :inherit [:managed-dependencies]}

  :plugins [[lein-parent "0.3.8"]]

  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]
                 [prismatic/schema]
                 [puppetlabs/http-client]
                 [ring/ring-codec]
                 [cheshire]
                 [trptcolin/versioneer]
                 [slingshot]]

  :repositories [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/"]
                 ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:provided {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}
             :defaults {:dependencies [[puppetlabs/trapperkeeper :classifier "test" :scope "test"]
                                       [puppetlabs/kitchensink :classifier "test" :scope "test"]
                                       [puppetlabs/trapperkeeper-webserver-jetty9]
                                       [puppetlabs/trapperkeeper-webserver-jetty9 :classifier "test"]
                                       [ring-mock "0.1.5"]]}
             :dev [:defaults {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}]
             :fips [:defaults {:dependencies [[org.bouncycastle/bctls-fips]
                                              [org.bouncycastle/bcpkix-fips]
                                              [org.bouncycastle/bc-fips]]
                               :jvm-opts ~(let [version (System/getProperty "java.version")
                                                [major minor _] (clojure.string/split version #"\.")
                                                unsupported-ex (ex-info "Unsupported major Java version. Expects 8 or 11."
                                                                 {:major major
                                                                  :minor minor})]
                                            (condp = (java.lang.Integer/parseInt major)
                                              1 (if (= 8 (java.lang.Integer/parseInt minor))
                                                  ["-Djava.security.properties==./dev-resources/java.security.jdk8-fips"]
                                                  (throw unsupported-ex))
                                              11 ["-Djava.security.properties==./dev-resources/java.security.jdk11-fips"]
                                              (throw unsupported-ex)))}]})

