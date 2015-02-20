(def ks-version "1.0.0")
(def tk-version "1.0.0")
(def tk-jetty-version "1.0.0")

(defn deploy-info
  [url]
  {:url           url
   :username      :env/nexus_jenkins_username
   :password      :env/nexus_jenkins_password
   :sign-releases false})

(defproject puppetlabs/dujour-version-check "0.1.0-SNAPSHOT"
  :description "Dujour Version Check library"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/schema "0.2.2"]
                 [puppetlabs/http-client "0.4.0"]
                 [ring/ring-codec "1.0.0"]
                 [cheshire "5.3.1"]
                 [trptcolin/versioneer "0.1.0"]
                 [slingshot "0.10.3"]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                  [ring-mock "0.1.5"]]}}
  )
