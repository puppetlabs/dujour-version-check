(ns puppetlabs.dujour.version-check-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.dujour.version-check :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [ring.util.codec :as codec]
            [schema.test :as schema-test]
            [slingshot.test]))

(use-fixtures :once schema-test/validate-schemas)

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

(defn malformed-response-app
  [_]
  {:status 123
   :body ""})

(defn empty-response-app
  [_]
  {:status 200
   :body "{}"})

(defn malformed-body-app
  [_]
  {:status 200
   :body "wow what's going on here"})

(defn extra-fields-app
  [_]
  {:status 200
   :body (json/encode {:newer true
                       :link "http://zombo.com"
                       :message "woooo"
                       :product "zombocom"
                       :version "1000000"
                       :you "can"
                       :do "anything"
                       :at "zombocom"})})

(deftest test-check-for-update
  (testing "logs the correct version information during a valid version-check"
    (with-test-logging
      (jetty9/with-test-webserver
        return-all-as-message-app port
        (let [return-val
              (check-for-update {:certname "some-certname" :cacert "some-cacert" :product-name "foo"}
                                (format "http://localhost:%s" port))]
          (is (= (:version return-val) "9000.0.0"))
          (is (:newer return-val))
          (is (logged? #"Newer version 9000.0.0 is available!" :info))))))

  (testing "filters out extra parameters"
    (with-test-logging
      (jetty9/with-test-webserver
        return-all-as-message-app port
        (let [return-val
              (check-for-update {:certname "some-certname"
                                 :cacert "some-cacert"
                                 :product-name "foo"
                                 :database-version "9.4"}
                                (format "http://localhost:%s" port))]
          (is (= (:version return-val) "9000.0.0"))
          (is (= ((json/parse-string (:message return-val)) "database-version") nil))
          (is (:newer return-val))
          (is (logged? #"Newer version 9000.0.0 is available!" :info))))))

  (testing "allows but does not return extra response fields"
    (with-test-logging
      (jetty9/with-test-webserver extra-fields-app port
        (is (= [:version :newer :link :product :message]
               (keys (check-for-update {:certname "some-certname"
                                        :cacert "some-cacert"
                                        :product-name "foo"
                                        :database-version "9.4"}
                                       (format "http://localhost:%s" port)))))))))

(deftest test-send-telemetry
  (testing "does not send the actual certname or cacert"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [certname "some-certname"
              cacert "some-cacert"
              return-val (send-telemetry {:certname certname
                                          :cacert cacert
                                          :product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port))
              message (json/parse-string (:message return-val))]
          (is (= (message "host-id") (get-hash certname)))
          (is (= (message "site-id") (get-hash cacert)))
          (is (nil? (message "certname")))
          (is (nil? (message "cacert")))))))

  (testing "sends agent_os instead of agent-os"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [agent-os {"debian" 15 "centos" 5}
              return-val (send-telemetry {:agent-os agent-os
                                          :product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port))
              message (json/parse-string (:message return-val))]
          (is (= (message "agent_os") agent-os))
          (is (nil? (message "agent-os")))))))

  (testing "sends puppet_agent_versions instead of puppet-agent-versions"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [puppet-agent-versions {"1.6.7" 15 "1.4.5" 5}
              return-val (send-telemetry {:puppet-agent-versions puppet-agent-versions
                                          :product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port))
              message (json/parse-string (:message return-val))]
          (is (= (message "puppet_agent_versions") puppet-agent-versions))
          (is (nil? (message "puppet-agent-versions")))))))

  (testing "sends agent_cloud_platforms instead of agent-cloud-platforms"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [agent-cloud-platforms {"azure" 15 "gce" 5}
              return-val (send-telemetry {:agent-cloud-platforms agent-cloud-platforms
                                          :product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port))
              message (json/parse-string (:message return-val))]
             (is (= (message "agent_cloud_platforms") agent-cloud-platforms))
             (is (nil? (message "agent-cloud-platforms")))))))

  (testing "doesn't clobber agent_os"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [agent_os {"debian" 15 "centos" 5}
              return-val (send-telemetry {:agent_os agent_os
                                          :product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port))
              message (json/parse-string (:message return-val))]
          (is (= (message "agent_os") agent_os))
          (is (nil? (message "agent-os")))))))

  (testing "only submits agent_os if both agent-os and agent_os are present"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [agent_os {"debian" 15 "centos" 5}
              agent-os {"debian" 20 "centos" 10}
              return-val (send-telemetry {:agent_os agent_os
                                          :agent-os agent-os
                                          :product-name "foo"
                                          :database-version "9.4"}
                                         (format "http://localhost:%s" port))
              message (json/parse-string (:message return-val))]
          ;; The original `agent_os` should be clobbered in this case.
          (is (= (message "agent_os") agent-os))
          (is (nil? (message "agent-os")))))))

  (testing "sends the version number"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val
              (send-telemetry {:product-name "foo"
                               :version "9.4"}
                              (format "http://localhost:%s" port))]
          (is (= ((json/parse-string (:message return-val)) "version") "9.4"))
          (is (= ((json/parse-string (:message return-val)) "product") "foo"))))))

  (testing "allows omitting certname and cacert for backwards compatibility"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val
              (send-telemetry {:product-name "foo"
                               :database-version "9.4"}
                              (format "http://localhost:%s" port))]
          (is (= (:version return-val) "9000.0.0")))))))


(deftest error-handling-update
  (testing "throws a slingshot exception when there is a connection error"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/connection-error]
                      (check-for-update {:product-name "foo"
                                         :database-version "9.4"}
                                        "http://localhost:1"))))))

  (testing "throws a slingshot exception when an error is returned by the server"
    (with-test-logging
      (jetty9/with-test-webserver server-error-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/http-error-code]
                      (check-for-update {:product-name "foo"
                                         :database-version "9.4"}
                                        (format "http://localhost:%s" port)))))))

  (testing "throws a slingshot exception when the server returns a bad response (catches apache http exceptions)"
    (with-test-logging
      (jetty9/with-test-webserver malformed-response-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/connection-error]
                      (check-for-update {:product-name "foo"}
                                        (format "http://localhost:%s" port)))))))

  (testing "throws a slingshot exception when the server returns valid but unexpected json"
    (with-test-logging
      (jetty9/with-test-webserver empty-response-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/unexpected-response]
                      (check-for-update {:product-name "foo"}
                                        (format "http://localhost:%s" port)))))))

  (testing "throws a slingshot exception when the server returns a malformed body"
    (with-test-logging
      (jetty9/with-test-webserver malformed-body-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/unexpected-response]
                      (check-for-update {:product-name "foo"}
                                        (format "http://localhost:%s" port))))))))

(deftest error-handling-telemetry
  (testing "throws a slingshot exception when there is a connection error"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/connection-error]
                      (send-telemetry {:product-name "foo"
                                       :database-version "9.4"}
                                      "http://localhost:1"))))))

  (testing "throws a slingshot exception when an error is returned by the server"
    (with-test-logging
      (jetty9/with-test-webserver server-error-app port
        (is (thrown+? [:kind :puppetlabs.dujour.version-check/http-error-code]
                      (send-telemetry {:product-name "foo"
                                       :database-version "9.4"}
                                      (format "http://localhost:%s" port))))))))

(deftest test-check-for-updates
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
          (is (= (:version @return-val) "9000.0.0")))))))

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
