(ns puppetlabs.dujour.version-check
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [ring.util.codec :as ring-codec]
            [puppetlabs.http.client.sync :as client]
            [cheshire.core :as json]
            [trptcolin.versioneer.core :as version]
            [slingshot.slingshot :as sling]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.set :as set]))

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
   (schema/optional-key :message) schema/Str})

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

(schema/defn ^:always-validate update-info :- (schema/maybe UpdateInfo)
  "Make a request to the puppetlabs server to determine the latest available
  version. Returns the JSON object received from the server, which
  is expected to be a map containing keys `:version`, `:newer`, and `:link`.
  Returns `nil` if the request does not succeed for some reason."
  [request-values :- RequestValues
   update-server-url :- schema/Str]
  (let [product-name (:product-name request-values)
        {:keys [group-id artifact-id]} (get-coords product-name)
        ;current-version (version group-id artifact-id (str (or (:version request-values) "")))
        current-version (str (or (:version request-values) (version group-id artifact-id "")))
        _ (log/errorf "current-version = %s" current-version)
        version-data {:version current-version}
        query-string (-> request-values
                         (dissoc :product-name)
                         (merge version-data)
                         ring-codec/form-encode)
        url (format "%s?product=%s&group=%s&%s" update-server-url artifact-id group-id query-string)
        {:keys [status body] :as resp} (client/get url
                                                   {:headers {"Accept" "application/json"}
                                                    :as :text})]
    (if (= status 200)
      (json/parse-string body true)
      (sling/throw+ {:type ::update-request-failed
                     :message resp}))))


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
        {:keys [version newer link] :as response} (try
                                                    (update-info request-values update-server-url)
                                                    (catch Throwable e
                                                      (log/debug e (format "Could not retrieve update information (%s)" update-server-url))))
        link-str (if link
                   (format " Visit %s for details." link)
                   "")
        update-msg (format "Newer version %s is available!%s" version link-str)]
    (when newer
      (log/info update-msg))
    response))

(defn- get-hash
  "Returns a SHA-512 encoded value of the given string."
  [data]
  (let [md (. java.security.MessageDigest getInstance "sha-512")]
    (.update md (.getBytes data))
    (let [bytes (.digest md)]
      (reduce #(str %1 (format "%02x" %2)) "" bytes))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn check-for-updates!
  ([request-values update-server-url]
    (check-for-updates! request-values update-server-url nil))
  ([request-values update-server-url callback-fn]
    (validate-config! request-values update-server-url)
    (future
      (let [keys-present-to-hash (vec (keys (select-keys request-values [:cacert :certname])))
            arguments (-> (ks/mapvals get-hash keys-present-to-hash request-values)
                          (set/rename-keys {:certname :host-id
                                            :cacert :site-id}))
            server-response (try
                              (version-check arguments update-server-url)
                              (catch Exception e
                                (log/warn e "Error occurred while checking for updates")
                                (throw e)))]
        (if-not (nil? callback-fn)
          (callback-fn server-response))))
    nil))

(defn get-version-string
  ([product-name]
    (get-version-string product-name default-group-id))
  ([product-name group-id]
    (version group-id product-name)))