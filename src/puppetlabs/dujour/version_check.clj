(ns puppetlabs.dujour.version-check
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [puppetlabs.http.client.sync :as client]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as schema]
            [slingshot.slingshot :refer [throw+]]
            [trptcolin.versioneer.core :as version])
  (:import com.fasterxml.jackson.core.JsonParseException
           java.io.IOException
           org.apache.http.HttpException))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-group-id "puppetlabs.packages")
(def default-update-server-url "http://updates.puppetlabs.com")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ProductCoords
  {:group-id schema/Str
   :artifact-id schema/Str})

(def ProductName
  (schema/conditional
    string? schema/Str
    map?    ProductCoords))

(def RequestValues
  {(schema/optional-key :certname) schema/Str
   (schema/optional-key :cacert) schema/Str
   :product-name ProductName
   schema/Any schema/Any})

(def UpdateInfo
  {:version schema/Str
   :newer schema/Bool
   :link schema/Str
   :product schema/Str
   (schema/optional-key :message) schema/Str
   (schema/optional-key :whitelist) {schema/Keyword {schema/Keyword schema/Str}}})

(def RequestResult
  {:status schema/Int
   :body schema/Any
   :resp schema/Any})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn schema-match?
  [schema obj]
  (nil? (schema/check schema obj)))

(schema/defn get-coords :- ProductCoords
  [product-name :- ProductName]
  (condp schema-match? product-name
    schema/Str {:group-id default-group-id
                :artifact-id product-name}
    ProductCoords product-name))

(schema/defn version* :- schema/Str
  "Get the version number of this installation."
  ([group-id artifact-id]
    (version* group-id artifact-id nil))
  ([group-id artifact-id default]
    (version/get-version group-id artifact-id default)))

(def version
  "Get the version number of this installation."
  (memoize version*))

(defn- throw-connection-error
  [cause]
  (throw+ {:kind ::connection-error
           :msg (.getMessage cause)
           :cause cause}))

(schema/defn ^:always-validate update-info-post :- RequestResult
  "Make a POST request to the puppetlabs server to determine the latest available
  version."
  [request-values :- RequestValues
   update-server-url :- schema/Str]
  (let [product-name (:product-name request-values)
        {:keys [group-id artifact-id]} (get-coords product-name)
        current-version (str (or (:version request-values) (version group-id artifact-id "")))
        version-data {:version current-version}
        request-body (-> request-values
                         (dissoc :product-name)
                         (set/rename-keys {:agent-os :agent_os
                                           :puppet-agent-versions :puppet_agent_versions})
                         (assoc "product" artifact-id)
                         (assoc "group" group-id)
                         (merge version-data)
                         json/generate-string)
        _ (log/tracef "Making update request to %s with data: %s" update-server-url request-body)
        {:keys [status body] :as resp} (try
                                         (client/post update-server-url
                                                      {:headers {"Accept" "application/json"}
                                                       :body request-body
                                                       :as :text})
                                         (catch HttpException e
                                           (throw-connection-error e))
                                         (catch IOException e
                                           (throw-connection-error e)))]

    (log/tracef "Received response from %s, status: %s, body: %s" update-server-url status body)
    {:status status :body body :resp resp}))

(defn- throw-unexpected-response
  [body]
  (throw+ {:kind ::unexpected-response
           :msg "Server returned HTTP code 200 but the body was not understood."
           :details {:body body}}))

(defn- parse-body
  [body]
  (try
    (let [update-response (json/parse-string body true)]
      (if (schema/check (merge UpdateInfo {schema/Any schema/Any}) update-response)
        (throw-unexpected-response body)
        (select-keys update-response [:version :newer :link :product :message :whitelist])))
    (catch JsonParseException _
      (throw-unexpected-response body))))

(schema/defn ^:always-validate update-info :- UpdateInfo
  "Make a request to the puppetlabs server to determine the latest available
  version. Attempts a POST request before falling back to a GET request.
  Returns the JSON object received from the server, which is expected to be
  a map containing keys `:version`, `:newer`, and `:link`. Returns `nil` if
  the request does not succeed for some reason."
  [request-values :- RequestValues
   update-server-url :- schema/Str]
  (let [{:keys [status body resp]} (update-info-post request-values update-server-url)]
    (cond
      (= status 200)
      (parse-body body)

      :else
      (throw+ {:kind ::http-error-code
               :msg (format "Server returned HTTP status code %s" status)
               :details {:status status
                         :body body}}))))

(defn validate-config!
  [request-values update-server-url]
  ;; if this ends up surfacing error messages that aren't very user-friendly,
  ;; we can improve the validation logic.
  (schema/validate RequestValues request-values)
  (schema/validate (schema/maybe schema/Str) update-server-url))

(defn- version-check
  "This will fetch the latest version number and log if the system
  is out of date."
  [request-values update-server-url]
  (log/debugf "Checking for newer versions of %s" (:product-name request-values))
  (let [update-server-url (or update-server-url default-update-server-url)
        {:keys [version newer link] :as response} (update-info request-values update-server-url)
        link-str (if link
                   (format " Visit %s for details." link)
                   "")
        update-msg (format "Newer version %s is available!%s" version link-str)]
    (when newer
      (log/info update-msg))
    response))

(defn get-hash
  "Returns a SHA-512 encoded value of the given string."
  [data]
  (let [md (. java.security.MessageDigest getInstance "sha-512")]
    (.update md (.getBytes data))
    (let [bytes (.digest md)]
      (reduce #(str %1 (format "%02x" %2)) "" bytes))))

(defn- update-with-ids
  [parameters]
  (let [keys-present-to-hash (vec (keys (select-keys parameters [:cacert :certname])))]
    (-> (ks/mapvals get-hash keys-present-to-hash parameters)
        (set/rename-keys {:certname :host-id
                          :cacert :site-id}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn ^:deprecated check-for-updates!
  "Check whether a newer version of the software is available and submit
  telemetry data. This is deprecated in favor of `check-for-update` and
  `send-telemetry` which separate these two operations (since users may want to
  opt out of telemetry while still being notified of new versions)."
  ([request-values update-server-url]
    (check-for-updates! request-values update-server-url nil))
  ([request-values update-server-url callback-fn]
    (validate-config! request-values update-server-url)
    (future
      (let [arguments (update-with-ids request-values)
            server-response (version-check arguments update-server-url)]
        (if-not (nil? callback-fn)
          (callback-fn server-response)
          server-response)))))

(schema/defn check-for-update
  "Check whether a newer version of the software is available. It does not
  submit any extra data beyond the product name and version. If a newer version
  is available it will log a message to that effect and return a map describing
  the newer version (see `UpdateInfo`)."
  [request-values :- RequestValues
   update-server-url :- (schema/maybe schema/Str)]
  (version-check (select-keys request-values [:product-name :version]) update-server-url))

(schema/defn send-telemetry
  "Submit telemetry data. This will submit a map of data to the telemetry
  service at the given url."
  [request-values :- RequestValues
   update-server-url :- schema/Str]
  (update-info (update-with-ids request-values) update-server-url))

(defn get-version-string
  ([product-name]
    (get-version-string product-name default-group-id))
  ([product-name group-id]
    (version group-id product-name)))
