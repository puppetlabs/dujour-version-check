(def ks-version "1.0.0")
(def tk-version "1.0.0")
(def tk-jetty-version "1.0.0")

(defproject puppetlabs/dujour-version-check "0.1.0-SNAPSHOT"
  :description "Dujour Version Check library"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [prismatic/schema "0.2.2"]
                 [puppetlabs/http-client "0.4.0"]
                 [ring/ring-codec "1.0.0"]
                 [cheshire "5.3.1"]
                 [trptcolin/versioneer "0.1.0"]
                 [slingshot "0.10.3"]]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                  [ring-mock "0.1.5"]]}}

  :aliases {"tk" ["trampoline" "run" "--config" "dev-resources/config.conf"]}

  :main puppetlabs.trapperkeeper.main

  )
