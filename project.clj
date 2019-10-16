(defproject puppetlabs/dujour-version-check "0.2.3-SNAPSHOT"
  :description "Dujour Version Check library"

  :parent-project {:coords [puppetlabs/clj-parent "4.2.4"]
                   :inherit [:managed-dependencies]}

  :plugins [[lein-parent "0.3.7"]]

  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]
                 [prismatic/schema]
                 [puppetlabs/http-client]
                 [ring/ring-codec]
                 [cheshire]
                 [org.bouncycastle/bcpkix-jdk15on]
                 [trptcolin/versioneer]
                 [slingshot]]

  :repositories [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/"]
                 ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 :classifier "test"]
                                  [ring-mock "0.1.5"]]}}
  )
