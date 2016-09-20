(ns puppetlabs.dujour.version-check-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.dujour.version-check :refer :all]
            [schema.test :as schema-test]
            [cheshire.core :as json]
            [ring.util.codec :as codec]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once schema-test/validate-schemas)

(def get-hash #'puppetlabs.dujour.version-check/get-hash)
(def version-check #'puppetlabs.dujour.version-check/version-check)

(deftest test-get-coords
  (testing "group-id should use the default if not specified"
    (is (= {:group-id    "puppetlabs.packages"
            :artifact-id "foo"}
           (get-coords "foo"))))
  (testing "should use group-id if specified"
    (is (= {:group-id    "foo.foo"
            :artifact-id "foo"}
           (get-coords {:group-id "foo.foo"
                        :artifact-id "foo"})))))

(defn parse-params [params encoding]
  (let [params (codec/form-decode params (str encoding))]
    (if (map? params) params {})))

(defn return-all-as-message-app
  [req]
  (let [query-string (:query-string req)
        get-params (if query-string (parse-params query-string "UTF-8"))
        body (:body req)
        post-params (if body (json/parse-string (slurp body)))
        params (if (nil? post-params) get-params post-params)]
    {:status 200
     :body (json/generate-string {:newer true
                                  :link "http://foo.com"
                                  :message (json/generate-string params)
                                  :product "foo"
                                  :version "9000.0.0"})}))

(defn server-error-app
  [_]
  {:status 500
   :body "aaaaaaaaaaaaaaaaaaaaaaaaaa"})

(deftest test-version-check
  (testing "logs the correct version information during a valid version-check"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (version-check {:certname "some-certname" :cacert "some-cacert" :product-name "foo"}
                       (format "http://localhost:%s" port))
        (is (logged? #"Newer version 9000.0.0 is available!" :info)))))

  (testing "logs the correct message during an invalid version-check"
    (with-test-logging
      (jetty9/with-test-webserver server-error-app port
        (version-check {:certname "some-certname" :cacert "some-cacert" :product-name "foo"}
                       (format "http://localhost:%s" port))
        (is (logged? #"Could not retrieve update information" :debug))))))

(deftest test-check-for-updates!
  (testing "logs the correct version information during a valid version-check"
    (with-test-logging
      (jetty9/with-test-webserver
        return-all-as-message-app port
        (let [return-val  (promise)
              callback-fn (fn [resp]
                            (deliver return-val resp))]
          (check-for-updates! {:certname "some-certname" :cacert "some-cacert" :product-name "foo"}
                              (format "http://localhost:%s" port) callback-fn)
          (is (= (:version @return-val) "9000.0.0"))
          (is (:newer @return-val))
          (is (logged? #"Newer version 9000.0.0 is available!" :info))))))

  (testing "accepts arbitrary parameters in the request-values map"
    (with-test-logging
      (jetty9/with-test-webserver
        return-all-as-message-app port
        (let [return-val  (promise)
              callback-fn (fn [resp]
                            (deliver return-val resp))]
          (check-for-updates! {:certname "some-certname"
                               :cacert "some-cacert"
                               :product-name "foo"
                               :database-version "9.4"}
                              (format "http://localhost:%s" port) callback-fn)
          (is (= (:version @return-val) "9000.0.0"))
          (is (= ((json/parse-string (:message @return-val)) "database-version") "9.4"))
          (is (:newer @return-val))
          (is (logged? #"Newer version 9000.0.0 is available!" :info))))))

  (testing "logs the correct message during an invalid version-check"
    (with-test-logging
      (jetty9/with-test-webserver server-error-app port
        (let [return-val  (promise)
              callback-fn (fn [resp]
                            (deliver return-val resp))]
          (check-for-updates! {:certname "some-certname" :cacert "some-cacert" :product-name "foo"}
                              (format "http://localhost:%s" port) callback-fn)
          (is (nil? @return-val))
          (is (logged? #"Could not retrieve update information" :debug))))))

  (testing "does not send the actual certname or cacert"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))
              certname "some-certname"
              cacert "some-cacert"
              _ (check-for-updates! {:certname         certname
                                     :cacert           cacert
                                     :product-name     "foo"
                                     :database-version "9.4"}
                                    (format "http://localhost:%s" port) callback-fn)
              message (json/parse-string (:message @return-val))]
          (is (= (message "host-id") (get-hash certname)))
          (is (= (message "site-id") (get-hash cacert)))
          (is (nil? (message "certname")))
          (is (nil? (message "cacert")))))))

  (testing "sends agent_os instead of agent-os"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))
              agent-os {"debian" 15 "centos" 5}
              _ (check-for-updates! {:agent-os agent-os
                                     :product-name     "foo"
                                     :database-version "9.4"}
                                    (format "http://localhost:%s" port) callback-fn)
              message (json/parse-string (:message @return-val))]
          (is (= (message "agent_os") agent-os))
          (is (nil? (message "agent-os")))))))

  (testing "sends puppet_agent_versions instead of puppet-agent-versions"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))
              puppet-agent-versions {"1.6.7" 15 "1.4.5" 5}
              _ (check-for-updates! {:puppet-agent-versions puppet-agent-versions
                                     :product-name     "foo"
                                     :database-version "9.4"}
                                    (format "http://localhost:%s" port) callback-fn)
              message (json/parse-string (:message @return-val))]
          (is (= (message "puppet_agent_versions") puppet-agent-versions))
          (is (nil? (message "puppet-agent-versions")))))))

  (testing "doesn't clobber agent_os"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))
              agent_os {"debian" 15 "centos" 5}
              _ (check-for-updates! {:agent_os agent_os
                                     :product-name     "foo"
                                     :database-version "9.4"}
                                    (format "http://localhost:%s" port) callback-fn)
              message (json/parse-string (:message @return-val))]
          (is (= (message "agent_os") agent_os))
          (is (nil? (message "agent-os")))))))

  (testing "only submits agent_os if both agent-os and agent_os are present"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))
              agent_os {"debian" 15 "centos" 5}
              agent-os {"debian" 20 "centos" 10}
              _ (check-for-updates! {:agent_os agent_os
                                     :agent-os agent-os
                                     :product-name     "foo"
                                     :database-version "9.4"}
                                    (format "http://localhost:%s" port) callback-fn)
              message (json/parse-string (:message @return-val))]
          ; The original `agent_os` should be clobbered in this case.
          (is (= (message "agent_os") agent-os))
          (is (nil? (message "agent-os")))))))

  (testing "sends the version number"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))]
          (check-for-updates! {:product-name "foo"
                               :version "9.4"}
                              (format "http://localhost:%s" port) callback-fn)
          (is (= ((json/parse-string (:message @return-val)) "version") "9.4"))
          (is (= ((json/parse-string (:message @return-val)) "product") "foo"))))))

  (testing "allows omitting certname and cacert for backwards compatibility"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))]
          (check-for-updates! {:product-name "foo"
                               :database-version "9.4"}
            (format "http://localhost:%s" port) callback-fn)
          (is (= (:version @return-val) "9000.0.0"))))))

  (testing "returns a future that can be dereferenced"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp) "return string")
              future (check-for-updates! {:product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port) callback-fn)
              result @future]
          (is (= "return string" result))
          (is (= (:version @return-val) "9000.0.0"))))))

  (testing "fails normally when connection is refused"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [result (check-for-updates! {:product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" 1) nil)]
          (is (nil? @result))))))

  (testing "fails normally with a bad reporting URL port"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [result (check-for-updates! {:product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://not-a-real-domain-for-sure:%s" 1) nil)]
          (is (nil? @result)))))))

(deftest test-get-version-string
  (testing "get-version-string returns the correct version string"
    (with-test-logging
      (jetty9/with-test-webserver
        return-all-as-message-app port
        (let [version-string (get-version-string "trapperkeeper-webserver-jetty9" "puppetlabs")]
          (is (not (.isEmpty version-string)))
          (is (re-matches #"^\d+.\d+.\d+" version-string)))))))

(deftest test-get-hash
  (testing "runs deterministically"
    (let [str (ks/uuid)]
      (is (= (get-hash str) (get-hash str))))))
