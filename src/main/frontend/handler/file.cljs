(ns frontend.handler.file
  (:refer-clojure :exclude [load-file])
  (:require ["/frontend/utils" :as utils]
            [borkdude.rewrite-edn :as rewrite]
            [cljs-bean.core :as bean]
            [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs.core.async.interop :refer [<p!]]
            [clojure.core.async :as async]
            [clojure.string :as string]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.format :as format]
            [frontend.fs :as fs]
            [frontend.fs.nfs :as nfs]
            [frontend.git :as git]
            [frontend.handler.common :as common-handler]
            [frontend.handler.extract :as extract-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]
            [frontend.debug :as debug]
            [frontend.mobile.util :as mobile]
            [clojure.set :as set]))

;; TODO: extract all git ops using a channel

(defn load-file
  [repo-url path]
  (->
   (p/let [content (fs/read-file (config/get-repo-dir repo-url) path)]
     content)
   (p/catch
       (fn [e]
         (println "Load file failed: " path)
         (js/console.error e)))))

(defn load-multiple-files
  [repo-url paths]
  (doall
   (mapv #(load-file repo-url %) paths)))

(defn- keep-formats
  [files formats]
  (filter
   (fn [file]
     (let [format (format/get-format file)]
       (contains? formats format)))
   files))

(defn- only-supported-formats
  [files]
  (keep-formats files (config/supported-formats)))

(defn- only-text-formats
  [files]
  (keep-formats files (config/text-formats)))

(defn- only-image-formats
  [files]
  (keep-formats files (config/img-formats)))

(defn restore-config!
  ([repo-url project-changed-check?]
   (restore-config! repo-url nil project-changed-check?))
  ([repo-url config-content _project-changed-check?]
   (let [config-content (if config-content config-content
                            (common-handler/get-config repo-url))]
     (when config-content
       (common-handler/reset-config! repo-url config-content)))))

(defn load-files
  [repo-url]
  (state/set-cloning! false)
  (state/set-loading-files! repo-url true)
  (p/let [files (git/list-files repo-url)
          files (bean/->clj files)
          config-content (load-file repo-url
                                    (config/get-config-path repo-url))
          files (if config-content
                  (let [config (restore-config! repo-url config-content true)]
                    (common-handler/remove-hidden-files files config identity))
                  files)]
    (only-supported-formats files)))

(defn load-files-contents!
  [repo-url files ok-handler]
  (let [images (only-image-formats files)
        files (only-text-formats files)]
    (-> (p/all (load-multiple-files repo-url files))
        (p/then (fn [contents]
                  (let [file-contents (cond->
                                        (zipmap files contents)

                                        (seq images)
                                        (merge (zipmap images (repeat (count images) ""))))
                        file-contents (for [[file content] file-contents]
                                        {:file/path (util/path-normalize file)
                                         :file/content content})]
                    (ok-handler file-contents))))
        (p/catch (fn [error]
                   (log/error :nfs/load-files-error repo-url)
                   (log/error :exception error))))))

(defn- page-exists-in-another-file
  "Conflict of files towards same page"
  [repo-url page file]
  (when-let [page-name (:block/name page)]
    (let [current-file (:file/path (db/get-page-file repo-url page-name))]
      (when (not= file current-file)
       current-file))))

(defn reset-file!
  ([repo-url file content]
   (reset-file! repo-url file content false))
  ([repo-url file content new-graph?]
   (let [electron-local-repo? (and (util/electron?)
                                   (config/local-db? repo-url))
         file (cond
                (and electron-local-repo?
                     util/win32?
                     (utils/win32 file))
                file

                (and electron-local-repo? (or
                                           util/win32?
                                           (not= "/" (first file))))
                (str (config/get-repo-dir repo-url) "/" file)

                (and (mobile/native-android?) (not= "/" (first file)))
                (str (config/get-repo-dir repo-url) "/" file)

                (and (mobile/native-ios?) (not= "/" (first file)))
                file

                :else
                file)
         file (util/path-normalize file)
         new? (nil? (db/entity [:file/path file]))]
     (db/set-file-content! repo-url file content)
     (let [format (format/get-format file)
           file-content [{:file/path file}]
           tx (if (contains? config/mldoc-support-formats format)
                (let [[pages blocks] (extract-handler/extract-blocks-pages repo-url file content)
                      first-page (first pages)
                      delete-blocks (->
                                     (concat
                                      (db/delete-file-blocks! repo-url file)
                                      (when first-page (db/delete-page-blocks repo-url (:block/name first-page))))
                                     (distinct))
                      _ (when-let [current-file (page-exists-in-another-file repo-url first-page file)]
                          (when (not= file current-file)
                            (let [error (str "Page already exists with another file: " current-file ", current file: " file)]
                              (state/pub-event! [:notification/show
                                                 {:content error
                                                  :status :error
                                                  :clear? false}]))))
                      block-ids (map (fn [block] {:block/uuid (:block/uuid block)}) blocks)
                      block-refs-ids (->> (mapcat :block/refs blocks)
                                          (filter (fn [ref] (and (vector? ref)
                                                                 (= :block/uuid (first ref)))))
                                          (map (fn [ref] {:block/uuid (second ref)}))
                                          (seq))
                      ;; To prevent "unique constraint" on datascript
                      block-ids (set/union (set block-ids) (set block-refs-ids))
                      pages (extract-handler/with-ref-pages pages blocks)
                      pages-index (map #(select-keys % [:block/name]) pages)]
                  ;; does order matter?
                  (concat file-content pages-index delete-blocks pages block-ids blocks))
                file-content)
           tx (concat tx [(let [t (tc/to-long (t/now))] ;; TODO: use file system timestamp?
                            (cond->
                              {:file/path file}
                              new?
                              (assoc :file/created-at t)))])]
       (db/transact! repo-url tx (when new-graph? {:new-graph? true}))))))

;; TODO: Remove this function in favor of `alter-files`
(defn alter-file
  [repo path content {:keys [reset? re-render-root? from-disk? skip-compare? new-graph?]
                      :or {reset? true
                           re-render-root? false
                           from-disk? false
                           skip-compare? false}}]
  (let [original-content (db/get-file repo path)
        write-file! (if from-disk?
                      #(p/resolved nil)
                      #(fs/write-file! repo (config/get-repo-dir repo) path content
                                       (assoc (when original-content {:old-content original-content})
                                              :skip-compare? skip-compare?)))]
    (if reset?
      (do
        (when-let [page-id (db/get-file-page-id path)]
          (db/transact! repo
            [[:db/retract page-id :block/alias]
             [:db/retract page-id :block/tags]]))
        (reset-file! repo path content new-graph?))
      (db/set-file-content! repo path content))
    (util/p-handle (write-file!)
                   (fn [_]
                     (when (= path (config/get-config-path repo))
                       (restore-config! repo true))
                     (when (= path (config/get-custom-css-path repo))
                       (ui-handler/add-style-if-exists!))
                     (when re-render-root? (ui-handler/re-render-root!)))
                   (fn [error]
                     (println "Write file failed, path: " path ", content: " content)
                     (log/error :write/failed error)))))

(defn set-file-content!
  [repo path new-content]
  (alter-file repo path new-content {:reset? false
                                     :re-render-root? false}))

(defn alter-files
  [repo files {:keys [reset? update-db?]
               :or {reset? false
                    update-db? true}
               :as opts}]
  ;; old file content
  (let [file->content (let [paths (map first files)]
                        (zipmap paths
                                (map (fn [path] (db/get-file repo path)) paths)))]
    ;; update db
    (when update-db?
      (doseq [[path content] files]
        (if reset?
          (reset-file! repo path content)
          (db/set-file-content! repo path content))))

    (when-let [chan (state/get-file-write-chan)]
      (let [chan-callback (:chan-callback opts)]
        (async/put! chan [repo files opts file->content])
        (when chan-callback
          (chan-callback))))))

(defn alter-files-handler!
  [repo files {:keys [finish-handler chan]} file->content]
  (let [write-file-f (fn [[path content]]
                       (when path
                         (let [original-content (get file->content path)]
                          (-> (p/let [_ (or
                                         (util/electron?)
                                         (nfs/check-directory-permission! repo))]
                                (debug/set-ack-step! path :write-file)
                                (fs/write-file! repo (config/get-repo-dir repo) path content
                                                {:old-content original-content}))
                              (p/catch (fn [error]
                                         (state/pub-event! [:notification/show
                                                            {:content (str "Failed to save the file " path ". Error: "
                                                                           (str error))
                                                             :status :error
                                                             :clear? false}])
                                         (state/pub-event! [:instrument {:type :write-file/failed
                                                                         :payload {:path path
                                                                                   :content-length (count content)
                                                                                   :error-str (str error)
                                                                                   :error error}}])
                                         (log/error :write-file/failed {:path path
                                                                        :content content
                                                                        :error error})))))))
        finish-handler (fn []
                         (when finish-handler
                           (finish-handler))
                         (ui-handler/re-render-file!))]
    (-> (p/all (map write-file-f files))
        (p/then (fn []
                  (finish-handler)
                  (when chan
                    (async/put! chan true))))
        (p/catch (fn [error]
                   (println "Alter files failed:")
                   (js/console.error error)
                   (async/put! chan false))))))

(defn remove-file!
  [repo file]
  (when-not (string/blank? file)
    (->
     (p/let [_ (or (config/local-db? repo) (git/remove-file repo file))
             _ (fs/unlink! repo (config/get-repo-path repo file) nil)]
       (when-let [file (db/entity repo [:file/path file])]
         (common-handler/check-changed-files-status)
         (let [file-id (:db/id file)
               page-id (db/get-file-page-id (:file/path file))
               tx-data (map
                         (fn [db-id]
                           [:db.fn/retractEntity db-id])
                         (remove nil? [file-id page-id]))]
           (when (seq tx-data)
             (db/transact! repo tx-data)))))
     (p/catch (fn [err]
                (js/console.error "error: " err))))))

(defn run-writes-chan!
  []
  (let [chan (state/get-file-write-chan)]
    (async/go-loop []
      (let [args (async/<! chan)
            files (second args)]

        (doseq [path (map first files)]
          (debug/set-ack-step! path :start-write-file))

        ;; return a channel
        (try
          (<p! (apply alter-files-handler! args))
          (catch js/Error e
            (log/error :file/write-failed e))))
      (recur))
    chan))

(defn watch-for-current-graph-dir!
  []
  (when (util/electron?)
    (when-let [repo (state/get-current-repo)]
      (when-let [dir (config/get-repo-dir repo)]
        (fs/watch-dir! dir)))))

(defn create-metadata-file
  [repo-url encrypted?]
  (let [repo-dir (config/get-repo-dir repo-url)
        path (str config/app-name "/" config/metadata-file)
        file-path (str "/" path)
        default-content (if encrypted? "{:db/encrypted? true}" "{}")]
    (p/let [_ (fs/mkdir-if-not-exists (str repo-dir "/" config/app-name))
            file-exists? (fs/create-if-not-exists repo-url repo-dir file-path default-content)]
      (when-not file-exists?
        (reset-file! repo-url path default-content)))))

(defn create-pages-metadata-file
  [repo-url]
  (let [repo-dir (config/get-repo-dir repo-url)
        path (str config/app-name "/" config/pages-metadata-file)
        file-path (str "/" path)
        default-content "{}"]
    (p/let [_ (fs/mkdir-if-not-exists (str repo-dir "/" config/app-name))
            file-exists? (fs/create-if-not-exists repo-url repo-dir file-path default-content)]
      (when-not file-exists?
        (reset-file! repo-url path default-content)))))

(defn edn-file-set-key-value
  [path k v]
  (when-let [repo (state/get-current-repo)]
    (when-let [content (db/get-file path)]
      (common-handler/read-config content)
      (let [result (try
                     (rewrite/parse-string content)
                     (catch js/Error e
                       (println "Parsing config file failed: ")
                       (js/console.dir e)
                       {}))
            ks (if (vector? k) k [k])
            new-result (rewrite/assoc-in result ks v)
            new-content (str new-result)]
        (set-file-content! repo path new-content)))))
