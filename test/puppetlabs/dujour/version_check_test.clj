(ns puppetlabs.dujour.version-check-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.dujour.version-check :refer :all]
            [schema.test :as schema-test]
            [cheshire.core :as json]
            [ring.util.codec :as codec]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.tools.logging :as log]))

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
  (let [params (parse-params (:query-string req) "UTF-8")]
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

  (testing "sends the version number"
    (with-test-logging
      (jetty9/with-test-webserver return-all-as-message-app port
        (let [return-val (promise)
              callback-fn (fn [resp] (deliver return-val resp))]
          (check-for-updates! {:product-name "foo"
                               :version "9.4"}
                              (format "http://localhost:%s" port) callback-fn)
          (log/errorf "return value = %s" @return-val)
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
