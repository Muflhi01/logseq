(ns frontend.handler.editor
  (:require ["/frontend/utils" :as utils]
            [cljs.core.match :refer [match]]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as w]
            [clojure.zip :as zip]
            [dommy.core :as dom]
            [frontend.commands :as commands
             :refer [*angle-bracket-caret-pos *show-block-commands
                     *show-commands *slash-caret-pos]]
            [frontend.config :as config]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db-schema :as db-schema]
            [frontend.db.model :as db-model]
            [frontend.db.utils :as db-utils]
            [frontend.diff :as diff]
            [frontend.format.block :as block]
            [frontend.format.mldoc :as mldoc]
            [frontend.fs :as fs]
            [frontend.handler.block :as block-handler]
            [frontend.handler.common :as common-handler]
            [frontend.handler.export :as export]
            [frontend.handler.image :as image-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.repeated :as repeated]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.route :as route-handler]
            [frontend.image :as image]
            [frontend.idb :as idb]
            [frontend.mobile.util :as mobile]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.modules.outliner.datascript :as ds]
            [frontend.modules.outliner.tree :as tree]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.template :as template]
            [frontend.text :as text]
            [frontend.utf8 :as utf8]
            [frontend.util :as util :refer [profile]]
            [frontend.util.clock :as clock]
            [frontend.util.cursor :as cursor]
            [frontend.util.drawer :as drawer]
            [frontend.util.marker :as marker]
            [frontend.util.page-property :as page-property]
            [frontend.util.property :as property]
            [frontend.util.priority :as priority]
            [frontend.util.thingatpt :as thingatpt]
            [frontend.util.list :as list]
            [goog.dom :as gdom]
            [goog.dom.classes :as gdom-classes]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [medley.core :as medley]
            [promesa.core :as p]
            [frontend.util.keycode :as keycode]))

;; FIXME: should support multiple images concurrently uploading


(defonce *asset-uploading? (atom false))
(defonce *asset-uploading-process (atom 0))
(defonce *selected-text (atom nil))

(defn- get-selection-and-format
  []
  (when-let [block (state/get-edit-block)]
    (when (:block/uuid block)
      (when-let [edit-id (state/get-edit-input-id)]
        (when-let [input (gdom/getElement edit-id)]
          (let [selection-start (util/get-selection-start input)
                selection-end (util/get-selection-end input)
                value (gobj/get input "value")
                selection (when (not= selection-start selection-end)
                            (subs value selection-start selection-end))
                selection-start (+ selection-start
                                   (count (take-while #(= " " %) selection)))
                selection-end (- selection-end
                                 (count (take-while #(= " " %) (reverse selection))))]
            {:selection-start selection-start
             :selection-end selection-end
             :selection (some-> selection
                                string/trim)
             :format (:block/format block)
             :value value
             :block block
             :edit-id edit-id
             :input input}))))))

(defn- format-text!
  [pattern-fn]
  (when-let [m (get-selection-and-format)]
    (let [{:keys [selection-start selection-end format selection value edit-id input]} m
          pattern (pattern-fn format)
          pattern-count (count pattern)
          pattern-prefix (subs value (max 0 (- selection-start pattern-count)) selection-start)
          pattern-suffix (subs value selection-end (min (count value) (+ selection-end pattern-count)))
          already-wrapped? (= pattern pattern-prefix pattern-suffix)
          prefix (if already-wrapped?
                   (subs value 0 (- selection-start pattern-count))
                   (subs value 0 selection-start))
          postfix (if already-wrapped?
                    (subs value (+ selection-end pattern-count))
                    (subs value selection-end))
          inner-value (cond-> selection
                        (not already-wrapped?)
                        (#(str pattern % pattern)))
          new-value (str prefix inner-value postfix)]
      (state/set-edit-content! edit-id new-value)
      (cond
        already-wrapped? (cursor/set-selection-to input (- selection-start pattern-count) (- selection-end pattern-count))
        selection (cursor/move-cursor-to input (+ selection-end pattern-count))
        :else (cursor/set-selection-to input (+ selection-start pattern-count) (+ selection-end pattern-count))))))

(defn bold-format! []
  (format-text! config/get-bold))

(defn italics-format! []
  (format-text! config/get-italic))

(defn highlight-format! []
  (when-let [block (state/get-edit-block)]
    (let [format (:block/format block)]
      (format-text! #(config/get-highlight format)))))

(defn strike-through-format! []
  (format-text! config/get-strike-through))

(defn html-link-format!
  ([]
   (html-link-format! nil))
  ([link]
   (when-let [m (get-selection-and-format)]
     (let [{:keys [selection-start selection-end format selection value edit-id input]} m
           cur-pos (cursor/pos input)
           empty-selection? (= selection-start selection-end)
           selection-link? (and selection (or (util/starts-with? selection "http://")
                                              (util/starts-with? selection "https://")))
           [content forward-pos] (cond
                                   empty-selection?
                                   (config/get-empty-link-and-forward-pos format)

                                   link
                                   (config/with-label-link format selection link)

                                   selection-link?
                                   (config/with-default-link format selection)

                                   :else
                                   (config/with-default-label format selection))
           new-value (str
                      (subs value 0 selection-start)
                      content
                      (subs value selection-end))
           cur-pos (or selection-start cur-pos)]
       (state/set-edit-content! edit-id new-value)
       (cursor/move-cursor-to input (+ cur-pos forward-pos))))))

(defn open-block-in-sidebar!
  [block-id]
  (when block-id
    (when-let [block (db/pull [:block/uuid block-id])]
      (let [page? (nil? (:block/page block))]
        (state/sidebar-add-block!
         (state/get-current-repo)
         (:db/id block)
         (if page? :page :block)
         block)))))

(defn reset-cursor-range!
  [node]
  (when node
    (state/set-cursor-range! (util/caret-range node))))

(defn restore-cursor-pos!
  [id markup]
  (when-let [node (gdom/getElement (str id))]
    (when-let [cursor-range (state/get-cursor-range)]
      (when-let [range cursor-range]
        (let [pos (:editor/pos @state/state)
              pos (or pos (diff/find-position markup range))]
          (cursor/move-cursor-to node pos)
          (state/set-state! :editor/pos nil))))))


(defn highlight-block!
  [block-uuid]
  (let [blocks (array-seq (js/document.getElementsByClassName (str block-uuid)))]
    (doseq [block blocks]
      (dom/add-class! block "block-highlight"))))

(defn unhighlight-blocks!
  []
  (let [blocks (some->> (array-seq (js/document.getElementsByClassName "block-highlight"))
                        (repeat 2)
                        (apply concat))]
    (doseq [block blocks]
      (gdom-classes/remove block "block-highlight"))))

(defn- get-edit-input-id-with-block-id
  [block-id]
  (when-let [first-block (util/get-first-block-by-id block-id)]
    (string/replace (gobj/get first-block "id")
                    "ls-block"
                    "edit-block")))

(defn clear-selection!
  []
  (util/select-unhighlight! (dom/by-class "selected"))
  (state/clear-selection!))

(defn- text-range-by-lst-fst-line [content [direction pos]]
  (case direction
    :up
    (let [last-new-line (or (string/last-index-of content \newline) -1)
          end (+ last-new-line pos 1)]
      (subs content 0 end))
    :down
    (-> (string/split-lines content)
        first
        (or "")
        (subs 0 pos))))

;; id: block dom id, "ls-block-counter-uuid"
(defn edit-block!
  ([block pos id]
   (edit-block! block pos id nil))
  ([block pos id {:keys [custom-content tail-len move-cursor?]
                  :or {tail-len 0
                       move-cursor? true}}]
   (when-not config/publishing?
     (when-let [block-id (:block/uuid block)]
       (let [block (or (db/pull [:block/uuid block-id]) block)
             edit-input-id (if (uuid? id)
                             (get-edit-input-id-with-block-id id)
                             (-> (str (subs id 0 (- (count id) 36)) block-id)
                                 (string/replace "ls-block" "edit-block")))
             content (or custom-content (:block/content block) "")
             content-length (count content)
             text-range (cond
                          (vector? pos)
                          (text-range-by-lst-fst-line content pos)

                          (and (> tail-len 0) (>= (count content) tail-len))
                          (subs content 0 (- (count content) tail-len))

                          (or (= :max pos) (<= content-length pos))
                          content

                          :else
                          (subs content 0 pos))
             content (-> (property/remove-built-in-properties (:block/format block)
                                                              content)
                         (drawer/remove-logbook))]
         (clear-selection!)
         (state/set-editing! edit-input-id content block text-range move-cursor?))))))

(defn- another-block-with-same-id-exists?
  [current-id block-id]
  (and (string? block-id)
       (util/uuid-string? block-id)
       (not= current-id (cljs.core/uuid block-id))
       (db/entity [:block/uuid (cljs.core/uuid block-id)])))

(defn- attach-page-properties-if-exists!
  [block]
  (if (and (:block/pre-block? block)
           (seq (:block/properties block)))
    (let [page-properties (:block/properties block)
          str->page (fn [n] (block/page-name->map n true))
          refs (->> page-properties
                    (filter (fn [[_ v]] (coll? v)))
                    (vals)
                    (apply concat)
                    (set)
                    (map str->page)
                    (concat (:block/refs block))
                    (util/distinct-by :block/name))
          {:keys [tags alias]} page-properties
          page-tx (let [id (:db/id (:block/page block))
                        retract-attributes (when id
                                             (mapv (fn [attribute]
                                                     [:db/retract id attribute])
                                                   [:block/properties :block/tags :block/alias]))
                        tags (->> (map str->page tags) (remove nil?))
                        alias (->> (map str->page alias) (remove nil?))
                        tx (cond-> {:db/id id
                                    :block/properties page-properties}
                             (seq tags)
                             (assoc :block/tags tags)
                             (seq alias)
                             (assoc :block/alias alias))]
                    (conj retract-attributes tx))]
      (assoc block
             :block/refs refs
             :db/other-tx page-tx))
    block))

(defn- remove-non-existed-refs!
  [refs]
  (remove (fn [x] (or
                   (and (vector? x)
                        (= :block/uuid (first x))
                        (nil? (db/entity x)))
                   (nil? x))) refs))

(defn- with-marker-time
  [content block format new-marker old-marker]
  (if (and (state/enable-timetracking?) new-marker)
    (try
      (let [logbook-exists? (and (:block/body block) (drawer/get-logbook (:block/body block)))
            new-marker (string/trim (string/lower-case (name new-marker)))
            old-marker (when old-marker (string/trim (string/lower-case (name old-marker))))
            new-content (cond
                          (or (and (nil? old-marker) (or (= new-marker "doing")
                                                         (= new-marker "now")))
                              (and (= old-marker "todo") (= new-marker "doing"))
                              (and (= old-marker "later") (= new-marker "now"))
                              (and (= old-marker new-marker "now") (not logbook-exists?))
                              (and (= old-marker new-marker "doing") (not logbook-exists?)))
                          (clock/clock-in format content)

                          (or
                           (and (= old-marker "doing") (= new-marker "todo"))
                           (and (= old-marker "now") (= new-marker "later"))
                           (and (contains? #{"now" "doing"} old-marker)
                                (= new-marker "done")))
                          (clock/clock-out format content)

                          :else
                          content)]
        new-content)
      (catch js/Error _e
        content))
    content))

(defn- with-timetracking
  [block value]
  (if (and (state/enable-timetracking?)
           (not= (:block/content block) value))
    (let [new-marker (first (util/safe-re-find marker/bare-marker-pattern (or value "")))
          new-value (with-marker-time value block (:block/format block)
                      new-marker
                      (:block/marker block))]
      new-value)
    value))

(defn wrap-parse-block
  [{:block/keys [content format left page uuid level pre-block?] :as block}]
  (let [block (or (and (:db/id block) (db/pull (:db/id block))) block)
        block (merge block
                     (block/parse-title-and-body uuid format pre-block? (:block/content block)))
        properties (:block/properties block)
        real-content (:block/content block)
        content (if (and (seq properties) real-content (not= real-content content))
                  (property/with-built-in-properties properties content format)
                  content)
        content (drawer/with-logbook block content)
        content (with-timetracking block content)
        first-block? (= left page)
        ast (mldoc/->edn (string/trim content) (mldoc/default-config format))
        first-elem-type (first (ffirst ast))
        first-elem-meta (second (ffirst ast))
        properties? (contains? #{"Property_Drawer" "Properties"} first-elem-type)
        markdown-heading? (and (= format :markdown)
                               (= "Heading" first-elem-type)
                               (nil? (:size first-elem-meta)))
        block-with-title? (mldoc/block-with-title? first-elem-type)
        content (string/triml content)
        content (string/replace content (util/format "((%s))" (str uuid)) "")
        [content content'] (cond
                             (and first-block? properties?)
                             [content content]

                             markdown-heading?
                             [content content]

                             :else
                             (let [content' (str (config/get-block-pattern format) (if block-with-title? " " "\n") content)]
                               [content content']))
        block (assoc block
                     :block/content content'
                     :block/format format)
        block (apply dissoc block (remove #{:block/pre-block?} db-schema/retract-attributes))
        block (block/parse-block block)
        block (if (and first-block? (:block/pre-block? block))
                block
                (dissoc block :block/pre-block?))
        block (update block :block/refs remove-non-existed-refs!)
        block (attach-page-properties-if-exists! block)
        new-properties (merge
                        (select-keys properties (property/built-in-properties))
                        (:block/properties block))]
    (-> block
        (dissoc :block/top?
                :block/bottom?)
        (assoc :block/content content
               :block/properties new-properties)
        (merge (if level {:block/level level} {})))))

(defn- save-block-inner!
  [repo block value {}]
  (let [block (assoc block :block/content value)
        block (apply dissoc block db-schema/retract-attributes)]
    (profile
     "Save block: "
     (let [block (wrap-parse-block block)]
       (-> (outliner-core/block block)
           (outliner-core/save-node))

       ;; sanitized page name changed
       (when-let [title (get-in block [:block/properties :title])]
         (when-let [old-page-name (:block/name (db/entity (:db/id (:block/page block))))]
           (when (and (:block/pre-block? block)
                      (not (string/blank? title))
                      (not= (util/page-name-sanity-lc title) old-page-name))
             (state/pub-event! [:page/title-property-changed old-page-name title]))))))

    (repo-handler/push-if-auto-enabled! repo)))

(defn save-block-if-changed!
  ([block value]
   (save-block-if-changed! block value nil))
  ([block value
    {:keys [force?]
     :as opts}]
   (let [{:block/keys [uuid page format repo content properties]} block
         repo (or repo (state/get-current-repo))
         format (or format (state/get-preferred-format))
         page (db/entity repo (:db/id page))
         block-id (when (map? properties) (get properties :id))
         content (-> (property/remove-built-in-properties format content)
                     (drawer/remove-logbook))]
     (cond
       (another-block-with-same-id-exists? uuid block-id)
       (notification/show!
        [:p.content
         (util/format "Block with the id % already exists!" block-id)]
        :error)

       force?
       (save-block-inner! repo block value opts)

       :else
       (let [content-changed? (not= (string/trim content) (string/trim value))]
         (when (and content-changed? page)
           (save-block-inner! repo block value opts)))))))

(defn- compute-fst-snd-block-text
  [value pos]
  (when (string? value)
    (let [fst-block-text (subs value 0 pos)
          snd-block-text (string/triml (subs value pos))]
      [fst-block-text snd-block-text])))

(defn outliner-insert-block!
  [config current-block new-block {:keys [sibling? txs-state]}]
  (let [ref-top-block? (and (:ref? config)
                            (not (:ref-child? config)))
        skip-save-current-block? (:skip-save-current-block? config)
        [current-node new-node]
        (mapv outliner-core/block [current-block new-block])
        has-children? (db/has-children? (state/get-current-repo)
                                        (tree/-get-id current-node))
        sibling? (cond
                   ref-top-block?
                   false

                   (boolean? sibling?)
                   sibling?

                   (util/collapsed? current-block)
                   true

                   :else
                   (not has-children?))
        txs-state' (or txs-state (ds/new-outliner-txs-state))]
    (ds/auto-transact!
     [txs-state txs-state']
     {:outliner-op :save-and-insert-node
      :skip-transact? false}
     (let [*blocks (atom [current-node])]
       (when-not skip-save-current-block?
         (outliner-core/save-node current-node {:txs-state txs-state}))
       (outliner-core/insert-node new-node current-node sibling? {:blocks-atom *blocks
                                                                  :txs-state txs-state})
       {:blocks @*blocks
        :sibling? sibling?}))))

(defn- block-self-alone-when-insert?
  [config uuid]
  (let [current-page (state/get-current-page)
        block-id (or
                  (and (:id config)
                       (util/uuid-string? (:id config))
                       (:id config))
                  (and current-page
                       (util/uuid-string? current-page)
                       current-page))]
    (= uuid (and block-id (medley/uuid block-id)))))

(defn insert-new-block-before-block-aux!
  [config block value
   {:keys [ok-handler]
    :as _opts}]
  (let [input (gdom/getElement (state/get-edit-input-id))
        pos (cursor/pos input)
        [fst-block-text snd-block-text] (compute-fst-snd-block-text value pos)
        current-block (assoc block :block/content snd-block-text)
        current-block (apply dissoc current-block db-schema/retract-attributes)
        current-block (wrap-parse-block current-block)
        new-m {:block/uuid (db/new-block-id)
               :block/content fst-block-text}
        prev-block (-> (merge (select-keys block [:block/parent :block/left :block/format
                                                  :block/page :block/journal?]) new-m)
                       (wrap-parse-block))
        left-block (db/pull (:db/id (:block/left block)))]
    (profile
     "outliner insert block"
     (let [txs-state (ds/new-outliner-txs-state)]
       (outliner-core/save-node (outliner-core/block current-block) {:txs-state txs-state})
       (let [sibling? (not= (:db/id left-block) (:db/id (:block/parent block)))]
         (outliner-insert-block! config left-block prev-block {:sibling? sibling?
                                                               :txs-state txs-state}))))
    (ok-handler prev-block)))

(defn insert-new-block-aux!
  [config
   {:block/keys [uuid]
    :as block}
   value
   {:keys [ok-handler]
    :as _opts}]
  (let [block-self? (block-self-alone-when-insert? config uuid)
        input (gdom/getElement (state/get-edit-input-id))
        pos (cursor/pos input)
        [fst-block-text snd-block-text] (compute-fst-snd-block-text value pos)
        current-block (assoc block :block/content fst-block-text)
        current-block (apply dissoc current-block db-schema/retract-attributes)
        current-block (wrap-parse-block current-block)
        new-m {:block/uuid (db/new-block-id)
               :block/content snd-block-text}
        next-block (-> (merge (select-keys block [:block/parent :block/left :block/format
                                                  :block/page :block/journal?]) new-m)
                       (wrap-parse-block))
        sibling? (when block-self? false)]
    (profile
     "outliner insert block"
     (outliner-insert-block! config current-block next-block {:sibling? sibling?}))
    ;; WORKAROUND: The block won't refresh itself even if the content is empty.
    (when block-self?
      (gobj/set input "value" ""))
    (profile "ok handler" (ok-handler next-block))))

(defn clear-when-saved!
  []
  (state/set-editor-show-input! nil)
  (state/set-editor-show-zotero! false)
  (state/set-editor-show-date-picker! false)
  (state/set-editor-show-page-search! false)
  (state/set-editor-show-block-search! false)
  (state/set-editor-show-template-search! false)
  (commands/restore-state true))

(defn get-state
  []
  (let [[{:keys [on-hide block block-id block-parent-id format sidebar?]} id config] (state/get-editor-args)
        node (gdom/getElement id)]
    (when node
      (let [value (gobj/get node "value")
            pos (util/get-selection-start node)]
        {:config config
         :on-hide on-hide
         :sidebar? sidebar?
         :format format
         :id id
         :block (or (db/pull [:block/uuid (:block/uuid block)]) block)
         :block-id block-id
         :block-parent-id block-parent-id
         :node node
         :value value
         :pos pos}))))

(defn insert-new-block!
  ([state]
   (insert-new-block! state nil))
  ([_state block-value]
   (when (and (not config/publishing?)
              (not= :insert (state/get-editor-op)))
     (state/set-editor-op! :insert)
     (when-let [state (get-state)]
       (let [{:keys [block value id config]} state
             value (if (string? block-value) block-value value)
             block-id (:block/uuid block)
             block (or (db/pull [:block/uuid block-id])
                       block)
             block-self? (block-self-alone-when-insert? config block-id)
             input (gdom/getElement (state/get-edit-input-id))
             pos (cursor/pos input)
             [fst-block-text snd-block-text] (compute-fst-snd-block-text value pos)
             insert-fn (match (mapv boolean [block-self? (seq fst-block-text) (seq snd-block-text)])
                         [true _ _] insert-new-block-aux!
                         [_ false true] insert-new-block-before-block-aux!
                         [_ _ _] insert-new-block-aux!)]
         (insert-fn config block value
                    {:ok-handler
                     (fn [last-block]
                       (edit-block! last-block 0 id)
                       (clear-when-saved!))}))))
   (state/set-editor-op! nil)))

(defn api-insert-new-block!
  [content {:keys [page block-uuid sibling? before? properties custom-uuid]
            :or {sibling? false
                 before? false}}]
  (when (or page block-uuid)
    (let [before? (if page false before?)
          sibling? (if before? true (if page false sibling?))
          block (if page
                  (db/entity [:block/name (util/page-name-sanity-lc page)])
                  (db/entity [:block/uuid block-uuid]))]
      (when block
        (let [last-block (when (not sibling?)
                           (let [children (:block/_parent block)
                                 blocks (db/sort-by-left children block)
                                 last-block-id (:db/id (last blocks))]
                             (when last-block-id
                               (db/pull last-block-id))))
              format (or
                      (:block/format block)
                      (db/get-page-format (:db/id block))
                      (state/get-preferred-format))
              content (if (seq properties)
                        (property/insert-properties format content properties)
                        content)
              new-block (-> (select-keys block [:block/page :block/journal?
                                                :block/journal-day])
                            (assoc :block/content content
                                   :block/format format))
              new-block (assoc new-block :block/page
                               (if page
                                 (:db/id block)
                                 (:db/id (:block/page new-block))))
              new-block (-> new-block
                            (wrap-parse-block)
                            (assoc :block/uuid (or custom-uuid (db/new-block-id))))
              [block-m sibling?] (cond
                                   before?
                                   (let [first-child? (->> [:block/parent :block/left]
                                                           (map #(:db/id (get block %)))
                                                           (apply =))
                                         block (db/pull (:db/id (:block/left block)))
                                         sibling? (if (or first-child? ;; insert as first child
                                                          (:block/name block))
                                                    false sibling?)]
                                     [block sibling?])

                                   sibling?
                                   [(db/pull (:db/id block)) sibling?]

                                   last-block
                                   [last-block true]

                                   block
                                   [(db/pull (:db/id block)) sibling?]

                                   ;; FIXME: assert
                                   :else
                                   nil)]

          (when block-m
            (outliner-insert-block! {:skip-save-current-block? true} block-m new-block {:sibling? sibling?})
            new-block))))))

(defn insert-first-page-block-if-not-exists!
  [page-name]
  (when (string? page-name)
    (when-let [page (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
      (when (db/page-empty? (state/get-current-repo) (:db/id page))
        (api-insert-new-block! "" {:page page-name})))))

(defn properties-block
  [properties format page]
  (let [content (property/insert-properties format "" properties)
        refs (block/get-page-refs-from-properties properties)]
    {:block/pre-block? true
     :block/uuid (db/new-block-id)
     :block/properties properties
     :block/properties-order (keys properties)
     :block/refs refs
     :block/left page
     :block/format format
     :block/content content
     :block/parent page
     :block/page page}))

(defn default-properties-block
  ([title format page]
   (default-properties-block title format page {}))
  ([title format page properties]
   (let [p (common-handler/get-page-default-properties title)
         ps (merge p properties)
         content (page-property/insert-properties format "" ps)
         refs (block/get-page-refs-from-properties properties)]
     {:block/pre-block? true
      :block/uuid (db/new-block-id)
      :block/properties ps
      :block/properties-order (keys ps)
      :block/refs refs
      :block/left page
      :block/format format
      :block/content content
      :block/parent page
      :block/page page})))

(defn add-default-title-property-if-needed!
  [page-name]
  (when (string? page-name)
    (when-let [page (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
      (when (db/page-empty? (state/get-current-repo) (:db/id page))
        (let [title (or (:block/original-name page)
                        (:block/name page))
              format (db/get-page-format page)
              create-title-property? (util/create-title-property? title)]
          (when create-title-property?
            (let [default-properties (default-properties-block title format (:db/id page))
                  new-empty-block (-> (dissoc default-properties :block/pre-block? :block/uuid :block/left :block/properties)
                                      (assoc :block/uuid (db/new-block-id)
                                             :block/content ""
                                             :block/left [:block/uuid (:block/uuid default-properties)]))]
              (db/transact! [default-properties new-empty-block])
              true)))))))

(defn update-timestamps-content!
  [{:block/keys [repeated? marker format] :as block} content]
  (if repeated?
    (let [scheduled-ast (block-handler/get-scheduled-ast block)
          deadline-ast (block-handler/get-deadline-ast block)
          content (some->> (filter repeated/repeated? [scheduled-ast deadline-ast])
                           (map (fn [ts]
                                  [(repeated/timestamp->text ts)
                                   (repeated/next-timestamp-text ts)]))
                           (reduce (fn [content [old new]]
                                     (string/replace content old new))
                                   content))
          content (string/replace-first
                   content marker
                   (case marker
                     "DOING"
                     "TODO"

                     "NOW"
                     "LATER"

                     marker))
          content (clock/clock-out format content)
          content (drawer/insert-drawer
                   format content "logbook"
                   (util/format (str (if (= :org format) "-" "*")
                                     " State \"DONE\" from \"%s\" [%s]")
                                marker
                                (date/get-date-time-string-3)))]
      content)
    content))

(defn check
  [{:block/keys [marker content repeated?] :as block}]
  (let [new-content (string/replace-first content marker "DONE")
        new-content (if repeated?
                      (update-timestamps-content! block content)
                      new-content)]
    (save-block-if-changed! block new-content)))

(defn uncheck
  [{:block/keys [content] :as block}]
  (let [marker (if (= :now (state/get-preferred-workflow))
                 "LATER"
                 "TODO")
        new-content (string/replace-first content "DONE" marker)]
    (save-block-if-changed! block new-content)))

(defn set-marker
  [{:block/keys [marker content] :as block} new-marker]
  (let [new-content (->
                     (if marker
                       (string/replace-first content (re-pattern (str "^" marker)) new-marker)
                       (str new-marker " " content))
                     (string/triml))]
    (save-block-if-changed! block new-content)))

(defn- rehighlight-selected-nodes
  ([]
   (rehighlight-selected-nodes (state/get-selection-blocks)))
  ([blocks]
   (let [blocks (doall
                 (map
                   (fn [block]
                     (when-let [id (gobj/get block "id")]
                       (when-let [block (gdom/getElement id)]
                         (dom/add-class! block "selected noselect")
                         block)))
                   blocks))]
     (state/set-selection-blocks! blocks))))

(defn- get-selected-blocks-with-children
  []
  (when-let [blocks (seq (state/get-selection-blocks))]
    (->> (mapcat (fn [block]
                   (cons block
                         (array-seq (dom/by-class block "ls-block"))))
                 blocks)
         distinct)))

(defn cycle-todos!
  []
  (when-let [blocks (seq (get-selected-blocks-with-children))]
    (let [workflow (state/get-preferred-workflow)
          ids (->> (distinct (map #(when-let [id (dom/attr % "blockid")]
                                     (uuid id)) blocks))
                   (remove nil?))]
      (doseq [id ids]
        (let [block (db/pull [:block/uuid id])
              new-marker (marker/cycle-marker-state workflow (:block/marker block))
              new-marker (if new-marker new-marker "")]
          (set-marker block new-marker)))
      (js/setTimeout #(rehighlight-selected-nodes blocks) 0))))

(defn cycle-todo!
  []
  #_:clj-kondo/ignore
  (if-let [blocks (seq (get-selected-blocks-with-children))]
    (cycle-todos!)
    (when (state/get-edit-block)
      (let [edit-input-id (state/get-edit-input-id)
            current-input (gdom/getElement edit-input-id)
            content (state/get-edit-content)
            format (or (db/get-page-format (state/get-current-page))
                       (state/get-preferred-format))
            [new-content marker] (marker/cycle-marker content format (state/get-preferred-workflow))
            new-content (string/triml new-content)
            new-pos (commands/compute-pos-delta-when-change-marker
                     content marker (cursor/pos current-input))]
        (state/set-edit-content! edit-input-id new-content)
        (cursor/move-cursor-to current-input new-pos)))))

(defn set-priority
  [{:block/keys [priority content] :as block} new-priority]
  (let [new-content (string/replace-first content
                                          (util/format "[#%s]" priority)
                                          (util/format "[#%s]" new-priority))]
    (save-block-if-changed! block new-content)))

(defn cycle-priority!
  []
  (when (state/get-edit-block)
    (let [format (or (db/get-page-format (state/get-current-page))
                     (state/get-preferred-format))
          input-id (state/get-edit-input-id)
          content (state/get-edit-content)
          new-priority (priority/cycle-priority-state content)
          new-value (priority/add-or-update-priority content format new-priority)]
      (state/set-edit-content! input-id new-value))))

(defn delete-block-aux!
  [{:block/keys [uuid repo] :as _block} children?]
  (let [repo (or repo (state/get-current-repo))
        block (db/pull repo '[*] [:block/uuid uuid])]
    (when block
      (->
       (outliner-core/block block)
       (outliner-core/delete-node children?)))))

(defn- move-to-prev-block
  [repo sibling-block format id value]
  (when (and repo sibling-block)
    (when-let [sibling-block-id (dom/attr sibling-block "blockid")]
      (when-let [block (db/pull repo '[*] [:block/uuid (uuid sibling-block-id)])]
        (let [original-content (util/trim-safe (:block/content block))
              value' (-> (property/remove-built-in-properties format original-content)
                         (drawer/remove-logbook))
              new-value (str value' value)
              tail-len (count value)
              pos (max
                   (if original-content
                     (utf8/length (utf8/encode original-content))
                     0)
                   0)]
          (edit-block! block pos id
                       {:custom-content new-value
                        :tail-len tail-len
                        :move-cursor? false}))))))

(defn delete-block!
  ([repo]
   (delete-block! repo true))
  ([repo delete-children?]
   (state/set-editor-op! :delete)
   (let [{:keys [id block-id block-parent-id value format]} (get-state)]
     (when block-id
       (let [page-id (:db/id (:block/page (db/entity [:block/uuid block-id])))
             page-blocks-count (and page-id (db/get-page-blocks-count repo page-id))]
         (when (> page-blocks-count 1)
           (let [block (db/entity [:block/uuid block-id])
                 has-children? (seq (:block/_parent block))
                 block (db/pull (:db/id block))
                 left (tree/-get-left (outliner-core/block block))
                 left-has-children? (and left
                                         (when-let [block-id (:block/uuid (:data left))]
                                           (let [block (db/entity [:block/uuid block-id])]
                                             (seq (:block/_parent block)))))]
             (when-not (and has-children? left-has-children?)
               (when block-parent-id
                 (let [block-parent (gdom/getElement block-parent-id)
                       sibling-block (util/get-prev-block-non-collapsed-non-embed block-parent)]
                   (delete-block-aux! block delete-children?)
                   (move-to-prev-block repo sibling-block format id value)))))))))
   (state/set-editor-op! nil)))

(defn- get-end-block-parent
  [end-block blocks]
  (if-let [parent (let [id (:db/id (:block/parent end-block))]
                    (some (fn [block] (when (= (:db/id block) id) block)) blocks))]
    (recur parent blocks)
    end-block))

(defn- get-top-level-end-node
  [blocks]
  (let [end-block (last blocks)
        end-block-parent (get-end-block-parent end-block blocks)]
    (outliner-core/block end-block-parent)))

(defn- reorder-blocks
  [blocks]
  (if (<= (count blocks) 1)
    blocks
    (let [[f s & _others] blocks]
      (if (or (= (:block/left s) {:db/id (:db/id f)})
              (and
               (let [parents (db/get-block-parents (state/get-current-repo)
                                                   (:block/uuid f)
                                                   100)]
                 (some #(= (:block/left s) {:db/id (:db/id %)})
                       parents))
               (not= (:block/left f) {:db/id (:db/id s)})))
        blocks
        (reverse blocks)))))

(defn delete-blocks!
  [repo dom-blocks]
  (let [block-uuids (distinct (map #(uuid (dom/attr % "blockid")) dom-blocks))]
    (when (seq block-uuids)
      (let [uuid->dom-block (zipmap block-uuids dom-blocks)
            lookup-refs (map (fn [id] [:block/uuid id]) block-uuids)
            blocks (db/pull-many repo '[*] lookup-refs)
            blocks (reorder-blocks blocks)
            start-node (outliner-core/block (first blocks))
            end-node (get-top-level-end-node blocks)
            block (first blocks)
            block-parent (get uuid->dom-block (:block/uuid block))
            sibling-block (when block-parent (util/get-prev-block-non-collapsed-non-embed block-parent))]
        (if (= start-node end-node)
          (delete-block-aux! (first blocks) true)
          (outliner-core/delete-nodes start-node end-node lookup-refs))
        (when sibling-block
          (move-to-prev-block repo sibling-block
                              (:block/format block)
                              (dom/attr sibling-block "id")
                              ""))))))

(defn- batch-set-block-property!
  "col: a collection of [block-id property-key property-value]."
  [col]
  #_:clj-kondo/ignore
  (when-let [repo (state/get-current-repo)]
    (ds/auto-transact!
     [txs-state (ds/new-outliner-txs-state)]
     {:outliner-op :set-block-properties
      :skip-transact? false}
     (doseq [[block-id key value] col]
       (let [block-id (if (string? block-id) (uuid block-id) block-id)]
         (when-let [block (db/entity [:block/uuid block-id])]
           (let [format (:block/format block)
                 content (:block/content block)
                 properties (:block/properties block)
                 properties (if (nil? value)
                              (dissoc properties key)
                              (assoc properties key value))
                 content (if (nil? value)
                           (property/remove-property format key content)
                           (property/insert-property format content key value))
                 content (property/remove-empty-properties content)
                 block (outliner-core/block {:block/uuid block-id
                                             :block/properties properties
                                             :block/content content})
                 input-pos (or (state/get-edit-pos) :max)]
             (outliner-core/save-node block {:txs-state txs-state}))))))

    (let [block-id (ffirst col)
          block-id (if (string? block-id) (uuid block-id) block-id)
          input-pos (or (state/get-edit-pos) :max)]
      ;; update editing input content
      (when-let [editing-block (state/get-edit-block)]
        (when (= (:block/uuid editing-block) block-id)
          (edit-block! editing-block
                       input-pos
                       (state/get-edit-input-id)))))))

(defn remove-block-property!
  [block-id key]
  (let [key (keyword key)]
    (batch-set-block-property! [[block-id key nil]])))

(defn set-block-property!
  [block-id key value]
  (let [key (keyword key)]
    (batch-set-block-property! [[block-id key value]])))

(defn set-block-query-properties!
  [block-id all-properties key add?]
  (when-let [block (db/entity [:block/uuid block-id])]
    (let [query-properties (-> (get-in block [:block/properties :query-properties] "")
                               (common-handler/safe-read-string "Failed to parse query properties"))
          query-properties (if (seq query-properties)
                             query-properties
                             all-properties)
          query-properties (if add?
                             (distinct (conj query-properties key))
                             (remove #{key} query-properties))
          query-properties (vec query-properties)]
      (if (seq query-properties)
        (set-block-property! block-id :query-properties (str query-properties))
        (remove-block-property! block-id :query-properties)))))

(defn set-block-timestamp!
  [block-id key value]
  (let [key (string/lower-case key)
        block-id (if (string? block-id) (uuid block-id) block-id)
        key (string/lower-case (str key))
        value (str value)]
    (when-let [block (db/pull [:block/uuid block-id])]
      (let [{:block/keys [content]} block
            content (or (state/get-edit-content) content)
            new-content (-> (text/remove-timestamp content key)
                            (text/add-timestamp key value))]
        (when (not= content new-content)
          (if-let [input-id (state/get-edit-input-id)]
            (state/set-edit-content! input-id new-content)
            (save-block-if-changed! block new-content)))))))

(defn- set-blocks-id!
  [block-ids]
  (let [block-ids (remove nil? block-ids)
        col (map (fn [block-id]
                   (let [block (db/entity [:block/uuid block-id])]
                     (when-not (:block/pre-block? block)
                       [block-id :id (str block-id)])))
              block-ids)]
    (batch-set-block-property! col)))

(defn copy-block-ref!
  ([block-id]
   (copy-block-ref! block-id #(str %)))
  ([block-id tap-clipboard]
   (set-blocks-id! [block-id])
   (util/copy-to-clipboard! (tap-clipboard block-id))))

(defn select-block!
  [block-uuid]
  (when-let [block (-> (str block-uuid)
                       (js/document.getElementsByClassName)
                       first)]
    (state/exit-editing-and-set-selected-blocks! [block])))

(defn- blocks-with-level
  "Should be sorted already."
  [blocks]
  (let [root (assoc (first blocks) :level 1)]
    (loop [m [[(:db/id root) root]]
           blocks (rest blocks)]
      (if (empty? blocks)
        m
        (let [block (first blocks)
              parent-id (:db/id (:block/parent block))
              parent-level (:level (second (first (filter (fn [x] (= (first x) parent-id)) m))))
              block (assoc block :level (inc parent-level))
              m' (vec (conj m [(:db/id block) block]))]
          (recur m' (rest blocks)))))))

(defn- blocks-vec->tree
  [blocks]
  (let [loc (reduce (fn [loc {:keys [level] :as block}]
                      (let [loc*
                            (loop [loc (zip/vector-zip (zip/root loc))
                                   level level]
                              (if (> level 1)
                                (if-let [down (zip/rightmost (zip/down loc))]
                                  (let [down-node (zip/node down)]
                                    (if (or (and (vector? down-node)
                                                 (>= (:level (first down-node)) (:level block)))
                                            (>= (:level down-node) (:level block)))
                                      down
                                      (recur down (dec level))))
                                  loc)
                                loc))
                            loc**
                            (if (vector? (zip/node loc*))
                              (zip/append-child loc* block)
                              (-> loc*
                                  zip/up
                                  (zip/append-child [block])))]
                        loc**)) (zip/vector-zip []) blocks)]

    (clojure.walk/postwalk (fn [e] (if (map? e) (dissoc e :level) e)) (zip/root loc))))

(defn- compose-copied-blocks-contents-&-block-tree
  [repo block-ids]
  (let [blocks (db-utils/pull-many repo '[*] (mapv (fn [id] [:block/uuid id]) block-ids))
        blocks* (flatten
                 (mapv (fn [b] (if (util/collapsed? b)
                                 (vec (tree/sort-blocks (db/get-block-children repo (:block/uuid b)) b))
                                 [b])) blocks))
        block-ids* (mapv :block/uuid blocks*)
        level-blocks (blocks-with-level blocks*)
        level-blocks-uuid-map (into {} (mapv (fn [b] [(:block/uuid b) b]) (map second level-blocks)))
        level-blocks (mapv (fn [uuid] (get level-blocks-uuid-map uuid)) block-ids*)
        tree (blocks-vec->tree level-blocks)
        top-level-block-uuids (mapv :block/uuid (filterv #(not (vector? %)) tree))
        exported-md-contents (export/export-blocks-as-markdown
                              repo top-level-block-uuids
                              (state/get-export-block-text-indent-style)
                              (into [] (state/get-export-block-text-remove-options)))]
    [exported-md-contents tree]))

(defn copy-selection-blocks
  []
  (when-let [blocks (seq (get-selected-blocks-with-children))]
    (let [repo (state/get-current-repo)
          ids (->> (distinct (map #(when-let [id (dom/attr % "blockid")]
                                     (uuid id)) blocks))
                   (remove nil?))
          [content tree] (compose-copied-blocks-contents-&-block-tree repo ids)
          block (db/pull [:block/uuid (first ids)])]
      (common-handler/copy-to-clipboard-without-id-property! (:block/format block) content)
      (state/set-copied-blocks content tree)
      (notification/show! "Copied!" :success))))

(defn copy-block-refs
  []
  (when-let [selected-blocks (seq (get-selected-blocks-with-children))]
    (let [blocks (->> (distinct (map #(when-let [id (dom/attr % "blockid")]
                                        (let [level (dom/attr % "level")]
                                          {:id (uuid id)
                                           :level (int level)}))
                                  selected-blocks))
                      (remove nil?))
          first-block (first blocks)
          first-root-level-index (ffirst
                                  (filter (fn [[_ block]] (= (:level block) 1))
                                          (map-indexed vector blocks)))
          root-level (atom (:level first-block))
          adjusted-blocks (map-indexed
                           (fn [index {:keys [id level]}]
                             {:id id
                              :level (if (< index first-root-level-index)
                                       (if (< level @root-level)
                                         (do
                                           (reset! root-level level)
                                           1)
                                         (inc (- level @root-level)))
                                       level)})
                           blocks)
          block (db/pull [:block/uuid (:id first-block)])
          copy-str (some->> adjusted-blocks
                            (map (fn [{:keys [id level]}]
                                   (condp = (:block/format block)
                                     :org
                                     (util/format (str (string/join (repeat level "*")) " ((%s))") id)
                                     :markdown
                                     (util/format (str (string/join (repeat (dec level) "\t")) "- ((%s))") id))))
                            (string/join "\n\n"))]
      (set-blocks-id! (map :id blocks))
      (util/copy-to-clipboard! copy-str))))

(defn copy-block-embeds
  []
  (when-let [blocks (seq (get-selected-blocks-with-children))]
    (let [ids (->> (distinct (map #(when-let [id (dom/attr % "blockid")]
                                     (uuid id)) blocks))
                   (remove nil?))
          ids-str (some->> ids
                           (map (fn [id] (util/format "{{embed ((%s))}}" id)))
                           (string/join "\n\n"))]
      (set-blocks-id! ids)
      (util/copy-to-clipboard! ids-str))))

(defn get-selected-toplevel-block-uuids
  []
  (when-let [blocks (seq (get-selected-blocks-with-children))]
    (let [repo (state/get-current-repo)
          block-ids (->> (distinct (map #(when-let [id (dom/attr % "blockid")]
                                           (uuid id)) blocks))
                         (remove nil?))
          blocks (db-utils/pull-many repo '[*] (mapv (fn [id] [:block/uuid id]) block-ids))
          page-id (:db/id (:block/page (first blocks)))
          blocks*
          (->> blocks
               ;; filter out blocks not belong to page with 'page-id'
               (remove (fn [block] (some-> (:db/id (:block/page block)) (not= page-id))))
               ;; expand collapsed blocks
               (mapv (fn [b] (if (util/collapsed? b)
                               (vec (tree/sort-blocks (db/get-block-children repo (:block/uuid b)) b))
                               [b])))
               (flatten))
          block-ids* (mapv :block/uuid blocks*)
          level-blocks (blocks-with-level blocks*)
          level-blocks-uuid-map (into {} (mapv (fn [b] [(:block/uuid b) b]) (map second level-blocks)))
          level-blocks (mapv (fn [uuid] (get level-blocks-uuid-map uuid)) block-ids*)
          tree (blocks-vec->tree level-blocks)
          top-level-block-uuids (mapv :block/uuid (filterv #(not (vector? %)) tree))]
      top-level-block-uuids)))

(defn cut-selection-blocks
  [copy?]
  (when copy? (copy-selection-blocks))
  (when-let [blocks (seq (get-selected-blocks-with-children))]
    ;; remove embeds, references and queries
    (let [blocks (remove (fn [block]
                           (or (= "true" (dom/attr block "data-transclude"))
                               (= "true" (dom/attr block "data-query")))) blocks)]
      (when (seq blocks)
        (let [repo (state/get-current-repo)]
          (delete-blocks! repo blocks))))))

(def url-regex
  "Didn't use link/plain-link as it is incorrectly detects words as urls."
  #"[^\s\(\[]+://[^\s\)\]]+")

(defn extract-nearest-link-from-text
  [text pos & additional-patterns]
  (let [page-pattern #"\[\[([^\]]+)]]"
        block-pattern #"\(\(([^\)]+)\)\)"
        tag-pattern #"#\S+"
        page-matches (util/re-pos page-pattern text)
        block-matches (util/re-pos block-pattern text)
        tag-matches (util/re-pos tag-pattern text)
        additional-matches (mapcat #(util/re-pos % text) additional-patterns)
        matches (->> (concat page-matches block-matches tag-matches additional-matches)
                     (remove nil?))
        [_ match] (first (sort-by
                          (fn [[start-pos content]]
                            (let [end-pos (+ start-pos (count content))]
                              (cond
                                (< pos start-pos)
                                (- pos start-pos)

                                (> pos end-pos)
                                (- end-pos pos)

                                :else
                                0)))
                          >
                          matches))]
    (when match
      (cond
        (some #(re-find % match) additional-patterns)
        match
        (string/starts-with? match "#")
        (subs match 1 (count match))
        :else
        (subs match 2 (- (count match) 2))))))

(defn- get-nearest-page-or-url
  "Return the nearest page-name (not dereferenced, may be an alias), block, tag or url"
  []
  (when-let [block (state/get-edit-block)]
    (when (:block/uuid block)
      (when-let [edit-id (state/get-edit-input-id)]
        (when-let [input (gdom/getElement edit-id)]
          (when-let [pos (cursor/pos input)]
            (let [value (gobj/get input "value")]
              (extract-nearest-link-from-text value pos url-regex))))))))

(defn- get-nearest-page
  "Return the nearest page-name (not dereferenced, may be an alias), block or tag"
  []
  (when-let [block (state/get-edit-block)]
    (when (:block/uuid block)
      (when-let [edit-id (state/get-edit-input-id)]
        (when-let [input (gdom/getElement edit-id)]
          (when-let [pos (cursor/pos input)]
            (let [value (gobj/get input "value")]
              (extract-nearest-link-from-text value pos))))))))

(defn follow-link-under-cursor!
  []
  (when-let [page (get-nearest-page-or-url)]
    (when-not (string/blank? page)
      (if (re-find url-regex page)
        (js/window.open page)
        (let [page-name (db-model/get-redirect-page-name page)]
          (state/clear-edit!)
          (insert-first-page-block-if-not-exists! page-name)
          (route-handler/redirect-to-page! page-name))))))

(defn open-link-in-sidebar!
  []
  (when-let [page (get-nearest-page)]
    (let [page-name (string/lower-case page)
          block? (util/uuid-string? page-name)]
      (when-let [page (db/get-page page-name)]
        (if block?
          (state/sidebar-add-block!
           (state/get-current-repo)
           (:db/id page)
           :block
           page)
          (state/sidebar-add-block!
           (state/get-current-repo)
           (:db/id page)
           :page
           {:page page}))))))

(defn zoom-in! []
  (if (state/editing?)
    (when-let [id (some-> (state/get-edit-block)
                          :block/uuid
                          ((fn [id] [:block/uuid id]))
                          db/entity
                          :block/uuid)]
      (let [pos (state/get-edit-pos)]
        (route-handler/redirect-to-page! id)
        (edit-block! {:block/uuid id} pos id)))
    (js/window.history.forward)))

(defn zoom-out!
  []
  (if (state/editing?)
    (let [page (state/get-current-page)
          block-id (and
                    (string? page)
                    (util/uuid-string? page)
                    (medley/uuid page))]
      (when block-id
        (let [block-parent (db/get-block-parent block-id)]
          (if-let [id (and
                       (nil? (:block/name block-parent))
                       (:block/uuid block-parent))]
            (do
              (route-handler/redirect-to-page! id)

              (edit-block! {:block/uuid block-id} :max block-id))
            (let [page-id (some-> (db/entity [:block/uuid block-id])
                                  :block/page
                                  :db/id)]

              (when-let [page-name (:block/name (db/entity page-id))]
                (route-handler/redirect-to-page! page-name)
                (edit-block! {:block/uuid block-id} :max block-id)))))))
    (js/window.history.back)))

(defn cut-block!
  [block-id]
  (when-let [block (db/pull [:block/uuid block-id])]
    (let [repo (state/get-current-repo)
          ;; TODO: support org mode
          [md-content _tree] (compose-copied-blocks-contents-&-block-tree repo [block-id])]
      (common-handler/copy-to-clipboard-without-id-property! (:block/format block) md-content)
      (delete-block-aux! block true))))

(defn clear-last-selected-block!
  []
  (let [block (state/drop-last-selection-block!)]
    (util/select-unhighlight! [block])))

(defn highlight-selection-area!
  [end-block]
  (when-let [start-block (state/get-selection-start-block)]
    (let [blocks (util/get-nodes-between-two-nodes start-block end-block "ls-block")
          direction (util/get-direction-between-two-nodes start-block end-block "ls-block")

          blocks (if (= :up direction)
                   (reverse blocks)
                   blocks)]
      (state/exit-editing-and-set-selected-blocks! blocks direction))))

(defn on-select-block
  [direction]
  (fn [_event]
    (cond
      ;; when editing, quit editing and select current block
      (state/editing?)
      (state/exit-editing-and-set-selected-blocks! [(gdom/getElement (state/get-editing-block-dom-id))])

      ;; when selection and one block selected, select next block
      (and (state/selection?) (== 1 (count (state/get-selection-blocks))))
      (let [f (if (= :up direction) util/get-prev-block-non-collapsed util/get-next-block-non-collapsed-skip)
            element (f (first (state/get-selection-blocks)))]
        (when element
          (state/conj-selection-block! element direction)))

      ;; if same direction, keep conj on same direction
      (and (state/selection?) (= direction (state/get-selection-direction)))
      (let [f (if (= :up direction) util/get-prev-block-non-collapsed util/get-next-block-non-collapsed-skip)
            first-last (if (= :up direction) first last)
            element (f (first-last (state/get-selection-blocks)))]
        (when element
          (state/conj-selection-block! element direction)))

      ;; if different direction, keep clear until one left
      (state/selection?)
      (clear-last-selected-block!))))

(defn save-block-aux!
  [block value opts]
  (let [value (string/trim value)]
    ;; FIXME: somehow frontend.components.editor's will-unmount event will loop forever
    ;; maybe we shouldn't save the block/file in "will-unmount" event?
    (save-block-if-changed! block value
                            (merge
                             {:init-properties (:block/properties block)}
                             opts))))

(defn save-block!
  ([repo block-or-uuid content]
   (let [block (if (or (uuid? block-or-uuid)
                       (string? block-or-uuid))
                 (db-model/query-block-by-uuid block-or-uuid) block-or-uuid)
         format (:block/format block)]
     (save-block! {:block block :repo repo :format format} content)))
  ([{:keys [block repo] :as _state} value]
   (when (:db/id (db/entity repo [:block/uuid (:block/uuid block)]))
     (save-block-aux! block value {}))))

(defn save-current-block!
  ([]
   (save-current-block! {}))
  ([{:keys [force?] :as opts}]
   ;; non English input method
   (when-not (state/editor-in-composition?)
     (when (state/get-current-repo)
       (when (and (not @commands/*show-commands)
                  (not @commands/*show-block-commands)
                  (not (state/get-editor-show-page-search?))
                  (not (state/get-editor-show-page-search-hashtag?))
                  (not (state/get-editor-show-block-search?))
                  (not (state/get-editor-show-date-picker?))
                  (not (state/get-editor-show-template-search?))
                  (not (state/get-editor-show-input)))
         (try
           (let [input-id (state/get-edit-input-id)
                 block (state/get-edit-block)
                 db-block (when-let [block-id (:block/uuid block)]
                            (db/pull [:block/uuid block-id]))
                 elem (and input-id (gdom/getElement input-id))
                 db-content (:block/content db-block)
                 db-content-without-heading (and db-content
                                                 (util/safe-subs db-content (:block/level db-block)))
                 value (and elem (gobj/get elem "value"))]
             (cond
               force?
               (save-block-aux! db-block value opts)

               (and block value db-content-without-heading
                    (not= (string/trim db-content-without-heading)
                          (string/trim value)))
               (save-block-aux! db-block value opts)))
           (catch js/Error error
             (log/error :save-block-failed error))))))))

(defn- clean-content!
  [format content]
  (->> (text/remove-level-spaces content format)
       (drawer/remove-logbook)
       (property/remove-properties format)
       string/trim))

(defn insert-command!
  [id command-output format {:keys [restore?]
                             :or {restore? true}
                             :as option}]
  (cond
    ;; replace string
    (string? command-output)
    (commands/insert! id command-output option)

    ;; steps
    (vector? command-output)
    (commands/handle-steps command-output format)

    (fn? command-output)
    (let [s (command-output)]
      (commands/insert! id s option))

    :else
    nil)

  (when restore?
    (let [restore-slash-caret-pos? (if (and
                                        (seq? command-output)
                                        (= :editor/click-hidden-file-input
                                           (ffirst command-output)))
                                     false
                                     true)]
      (commands/restore-state restore-slash-caret-pos?))))

(defn get-asset-file-link
  [format url file-name image?]
  (let [pdf? (and url (string/ends-with? url ".pdf"))]
    (case (keyword format)
      :markdown (util/format (str (when (or image? pdf?) "!") "[%s](%s)") file-name url)
      :org (if image?
             (util/format "[[%s]]" url)
             (util/format "[[%s][%s]]" url file-name))
      nil)))

(defn ensure-assets-dir!
  [repo]
  (let [repo-dir (config/get-repo-dir repo)
        assets-dir "assets"]
    (p/then
     (fs/mkdir-if-not-exists (str repo-dir "/" assets-dir))
     (fn [] [repo-dir assets-dir]))))

(defn save-assets!
  ([_ repo files]
   (p/let [[repo-dir assets-dir] (ensure-assets-dir! repo)]
     (save-assets! repo repo-dir assets-dir files
                   (fn [index file-base]
                     ;; TODO: maybe there're other chars we need to handle?
                     (let [file-base (-> file-base
                                         (string/replace " " "_")
                                         (string/replace "%" "_")
                                         (string/replace "/" "_"))
                           file-name (str file-base "_" (.now js/Date) "_" index)]
                       (string/replace file-name #"_+" "_"))))))
  ([repo dir path files gen-filename]
   (p/all
    (for [[index ^js file] (map-indexed vector files)]
      ;; WARN file name maybe fully qualified path when paste file
      (let [file-name (util/node-path.basename (.-name file))
            [file-base ext] (if file-name
                              (let [last-dot-index (string/last-index-of file-name ".")]
                                [(subs file-name 0 last-dot-index)
                                 (subs file-name last-dot-index)])
                              ["" ""])
            filename (str (gen-filename index file-base) ext)
            filename (str path "/" filename)]
                                        ;(js/console.debug "Write asset #" dir filename file)
        (if (util/electron?)
          (let [from (.-path file)
                from (if (string/blank? from) nil from)]
            (p/then (js/window.apis.copyFileToAssets dir filename from)
                    #(p/resolved [filename (if (string? %) (js/File. #js[] %) file) (.join util/node-path dir filename)])))
          (p/then (fs/write-file! repo dir filename (.stream file) nil)
                  #(p/resolved [filename file]))))))))

(defonce *assets-url-cache (atom {}))

(defn make-asset-url
  [path] ;; path start with "/assets" or compatible for "../assets"
  (let [repo-dir (config/get-repo-dir (state/get-current-repo))
        path (string/replace path "../" "/")]
    (cond
      (util/electron?)
      (str "assets://" repo-dir path)

      (mobile/native-android?)
      (mobile/convert-file-src
       (str "file://" repo-dir path))

      (mobile/native-ios?)
      (mobile/convert-file-src
       (str repo-dir path))

      :else
      (let [handle-path (str "handle" repo-dir path)
            cached-url (get @*assets-url-cache (keyword handle-path))]
        (if cached-url
          (p/resolved cached-url)
          (p/let [handle (idb/get-item handle-path)
                  file (and handle (.getFile handle))]
            (when file
              (p/let [url (js/URL.createObjectURL file)]
                (swap! *assets-url-cache assoc (keyword handle-path) url)
                url))))))))

(defn delete-asset-of-block!
  [{:keys [repo href full-text block-id local? delete-local?] :as _opts}]
  (let [block (db-model/query-block-by-uuid block-id)
        _ (or block (throw (str block-id " not exists")))
        text (:block/content block)
        content (string/replace text full-text "")]
    (save-block! repo block content)
    (when (and local? delete-local?)
      ;; FIXME: should be relative to current block page path
      (when-let [href (if (util/electron?) href (second (re-find #"\((.+)\)$" full-text)))]
        (fs/unlink! repo
                    (config/get-repo-path
                     repo (-> href
                              (string/replace #"^../" "/")
                              (string/replace #"^assets://" ""))) nil)))))

;; assets/journals_2021_02_03_1612350230540_0.png
(defn resolve-relative-path
  [file-path]
  (if-let [current-file (or (db-model/get-block-file-path (state/get-edit-block))
                            ;; fix dummy file path of page
                            (and (util/electron?)
                                 (util/node-path.join
                                  (config/get-repo-dir (state/get-current-repo))
                                  (config/get-pages-directory) "_.md")))]
    (util/get-relative-path current-file file-path)
    file-path))

(defn upload-asset
  [id ^js files format uploading? drop-or-paste?]
  (let [repo (state/get-current-repo)
        block (state/get-edit-block)]
    (if (config/local-db? repo)
      (-> (save-assets! block repo (js->clj files))
          (p/then
           (fn [res]
             (when-let [[asset-file-name file full-file-path] (and (seq res) (first res))]
               (let [image? (util/ext-of-image? asset-file-name)]
                 (insert-command!
                  id
                  (get-asset-file-link format (resolve-relative-path (or full-file-path asset-file-name))
                                       (if file (.-name file) (if image? "image" "asset"))
                                       image?)
                  format
                  {:last-pattern (if drop-or-paste? "" (state/get-editor-command-trigger))
                   :restore?     true})))))
          (p/finally
            (fn []
              (reset! uploading? false)
              (reset! *asset-uploading? false)
              (reset! *asset-uploading-process 0))))
      (image/upload
       files
       (fn [file file-name file-type]
         (image-handler/request-presigned-url
          file file-name file-type
          uploading?
          (fn [signed-url]
            (insert-command! id
                             (get-asset-file-link format signed-url file-name true)
                             format
                             {:last-pattern (if drop-or-paste? "" (state/get-editor-command-trigger))
                              :restore?     true})

            (reset! *asset-uploading? false)
            (reset! *asset-uploading-process 0))
          (fn [e]
            (let [process (* (/ (gobj/get e "loaded")
                                (gobj/get e "total"))
                             100)]
              (reset! *asset-uploading? false)
              (reset! *asset-uploading-process process)))))))))

;; Editor should track some useful information, like editor modes.
;; For example:
;; 1. Which file format is it, markdown or org mode?
;; 2. Is it in the properties area? Then we can enable the ":" autopair
(def autopair-map
  {"[" "]"
   "{" "}"
   "(" ")"
   "`" "`"
   "~" "~"
   "*" "*"
   "_" "_"
   "^" "^"
   "=" "="
   "/" "/"
   "+" "+"})
;; ":" ":"                              ; TODO: only properties editing and org mode tag


(def reversed-autopair-map
  (zipmap (vals autopair-map)
          (keys autopair-map)))

(def autopair-when-selected
  #{"*" "^" "_" "=" "+" "/"})

(def delete-map
  (assoc autopair-map
         "$" "$"
         ":" ":"))

(defn autopair
  [input-id prefix _format _option]
  (let [value (get autopair-map prefix)
        selected (util/get-selected-text)
        postfix (str selected value)
        value (str prefix postfix)
        input (gdom/getElement input-id)]
    (when value
      (when-not (string/blank? selected) (reset! *selected-text selected))
      (let [[prefix _pos] (commands/simple-replace! input-id value selected
                                                    {:backward-pos (count postfix)
                                                     :check-fn (fn [new-value prefix-pos]
                                                                 (when (>= prefix-pos 0)
                                                                   [(subs new-value prefix-pos (+ prefix-pos 2))
                                                                    (+ prefix-pos 2)]))})]
        (case prefix
          "[["
          (do
            (commands/handle-step [:editor/search-page])
            (reset! commands/*slash-caret-pos (cursor/get-caret-pos input)))

          "(("
          (do
            (commands/handle-step [:editor/search-block :reference])
            (reset! commands/*slash-caret-pos (cursor/get-caret-pos input)))

          nil)))))

(defn surround-by?
  [input before end]
  (when input
    (let [value (gobj/get input "value")
          pos (cursor/pos input)]
      (text/surround-by? value pos before end))))

(defn wrapped-by?
  [input before end]
  (when input
    (let [value (gobj/get input "value")
          pos (dec (cursor/pos input))]
      (when (>= pos 0)
        (text/wrapped-by? value pos before end)))))

(defn get-matched-pages
  "Return matched page names"
  [q]
  (let [block (state/get-edit-block)
        editing-page (and block
                          (when-let [page-id (:db/id (:block/page block))]
                            (:block/name (db/entity page-id))))
        pages (search/page-search q 20)]
    (if editing-page
      ;; To prevent self references
      (remove (fn [p] (= (util/page-name-sanity-lc p) editing-page)) pages)
      pages)))

(defn get-matched-blocks
  [q block-id]
  ;; remove current block
  (let [current-block (state/get-edit-block)
        block-parents (set (->> (db/get-block-parents (state/get-current-repo)
                                                      block-id
                                                      99)
                                (map (comp str :block/uuid))))
        current-and-parents (set/union #{(str (:block/uuid current-block))} block-parents)]
    (p/let [result (search/block-search (state/get-current-repo) q {:limit 20})]
      (remove
       (fn [h]
         (contains? current-and-parents (:block/uuid h)))
       result))))

(defn get-matched-templates
  [q]
  (search/template-search q))

(defn get-matched-commands
  [input]
  (try
    (let [edit-content (or (gobj/get input "value") "")
          pos (cursor/pos input)
          last-slash-caret-pos (:pos @*slash-caret-pos)
          last-command (and last-slash-caret-pos (subs edit-content last-slash-caret-pos pos))]
      (when (> pos 0)
        (or
         (and (= (state/get-editor-command-trigger) (util/nth-safe edit-content (dec pos)))
              @commands/*initial-commands)
         (and last-command
              (commands/get-matched-commands last-command)))))
    (catch js/Error e
      (js/console.error e)
      nil)))

(defn get-matched-block-commands
  [input]
  (try
    (let [edit-content (gobj/get input "value")
          pos (cursor/pos input)
          last-command (subs edit-content
                             (:pos @*angle-bracket-caret-pos)
                             pos)]
      (when (> pos 0)
        (or
         (and (= \< (util/nth-safe edit-content (dec pos)))
              (commands/block-commands-map))
         (and last-command
              (commands/get-matched-commands
               last-command
               (commands/block-commands-map))))))
    (catch js/Error _error
      nil)))

(defn auto-complete?
  []
  (or @*show-commands
      @*show-block-commands
      @*asset-uploading?
      (state/get-editor-show-input)
      (state/get-editor-show-page-search?)
      (state/get-editor-show-block-search?)
      (state/get-editor-show-template-search?)
      (state/get-editor-show-date-picker?)))

(defn get-current-input-char
  [input]
  (when-let [pos (cursor/pos input)]
    (let [value (gobj/get input "value")]
      (when (and (>= (count value) (inc pos))
                 (>= pos 1))
        (util/nth-safe value pos)))))

(defn- reorder-selected-blocks
  [blocks]
  (let [repo (state/get-current-repo)
        lookup-refs (->> (map (fn [block] (when-let [id (dom/attr block "blockid")]
                                            [:block/uuid (medley/uuid id)])) blocks)
                         (remove nil?))
        blocks (db/pull-many repo '[*] lookup-refs)]
    (reorder-blocks blocks)))

(defn move-up-down
  [up?]
  (fn [event]
    (util/stop event)
    (let [edit-block-id (:block/uuid (state/get-edit-block))
          move-nodes (fn [blocks]
                       (let [nodes (mapv outliner-core/block blocks)]
                         (outliner-core/move-nodes nodes up?)
                         (rehighlight-selected-nodes)
                         (let [block-node (util/get-first-block-by-id (:block/uuid (first blocks)))]
                           (.scrollIntoView block-node #js {:behavior "smooth" :block "nearest"}))))]
      (if edit-block-id
        (when-let [block (db/pull [:block/uuid edit-block-id])]
          (let [blocks [block]]
            (move-nodes blocks))
          (when-let [input-id (state/get-edit-input-id)]
            (when-let [input (gdom/getElement input-id)]
              (.focus input))))
        (let [blocks (-> (state/get-selection-blocks)
                         reorder-selected-blocks)
              blocks (filter #(= (:block/parent %) (:block/parent (first blocks))) blocks)]
          (when (seq blocks)
            (move-nodes blocks)))))))

;; selections
(defn on-tab
  "direction = :left|:right, only indent or outdent when blocks are siblings"
  [direction]
  (let [blocks-dom-nodes (state/get-selection-blocks)
        blocks (seq (reorder-selected-blocks blocks-dom-nodes))]
    (when (seq blocks)
      (let [end-node (get-top-level-end-node blocks)
            end-node-parent (tree/-get-parent end-node)
            top-level-nodes (->> (filter #(= (get-in end-node-parent [:data :db/id])
                                             (get-in % [:block/parent :db/id])) blocks)
                                 (map outliner-core/block))]
        (outliner-core/indent-outdent-nodes top-level-nodes (= direction :right))
        (rehighlight-selected-nodes)))))

(defn- get-link [format link label]
  (let [link (or link "")
        label (or label "")]
    (case (keyword format)
      :markdown (util/format "[%s](%s)" label link)
      :org (util/format "[[%s][%s]]" link label)
      nil)))

(defn- get-image-link
  [format link label]
  (let [link (or link "")
        label (or label "")]
    (case (keyword format)
      :markdown (util/format "![%s](%s)" label link)
      :org (util/format "[[%s]]"))))

(defn handle-command-input [command id format m]
  ;; TODO: Add error handling for when user doesn't provide a required field.
  ;; (The current behavior is to just revert back to the editor.)
  (case command

    :link (let [{:keys [link label]} m]
            (when-not (or (string/blank? link) (string/blank? label))
              (insert-command!
               id
               (get-link format link label)
               format
               {:last-pattern (str (state/get-editor-command-trigger) "link")})))

    :image-link (let [{:keys [link label]} m]
                  (when (not (string/blank? link))
                    (insert-command!
                     id
                     (get-image-link format link label)
                     format
                     {:last-pattern (str (state/get-editor-command-trigger) "link")})))

    nil)

  (state/set-editor-show-input! nil)

  (when-let [saved-cursor (get @state/state :editor/last-saved-cursor)]
    (when-let [input (gdom/getElement id)]
      (.focus input)
      (cursor/move-cursor-to input saved-cursor))))

(defn get-search-q
  []
  (when-let [id (state/get-edit-input-id)]
    (when-let [input (gdom/getElement id)]
      (let [current-pos (cursor/pos input)
            pos (:editor/last-saved-cursor @state/state)
            edit-content (or (state/sub [:editor/content id]) "")]
        (or
         @*selected-text
         (util/safe-subs edit-content pos current-pos))))))

(defn close-autocomplete-if-outside
  [input]
  (when (and input
             (or (state/get-editor-show-page-search?)
                 (state/get-editor-show-page-search-hashtag?)
                 (state/get-editor-show-block-search?))
             (not (wrapped-by? input "[[" "]]")))
    (when (get-search-q)
      (let [value (gobj/get input "value")
            pos (:editor/last-saved-cursor @state/state)
            current-pos (cursor/pos input)
            between (util/safe-subs value (min pos current-pos) (max pos current-pos))]
        (when (and between
                   (or
                    (string/includes? between "[")
                    (string/includes? between "]")
                    (string/includes? between "(")
                    (string/includes? between ")")))
          (state/set-editor-show-block-search! false)
          (state/set-editor-show-page-search! false)
          (state/set-editor-show-page-search-hashtag! false))))))

(defn resize-image!
  [block-id metadata full_text size]
  (let [new-meta (merge metadata size)
        image-part (first (string/split full_text #"\{"))
        new-full-text (str image-part (pr-str new-meta))
        block (db/pull [:block/uuid block-id])
        value (:block/content block)
        new-value (string/replace value full_text new-full-text)]
    (save-block-aux! block new-value {})))

(defn- mark-last-input-time!
  [repo]
  (when repo
    (state/set-editor-last-input-time! repo (util/time-ms))
    (db/clear-repo-persistent-job! repo)))

(defonce *auto-save-timeout (atom nil))
(defn edit-box-on-change!
  [e block id]
  (let [value (util/evalue e)
        repo (state/get-current-repo)]
    (state/set-edit-content! id value false)
    (when @*auto-save-timeout
      (js/clearTimeout @*auto-save-timeout))
    (mark-last-input-time! repo)
    (when-not
        (and
         (= (:db/id (:block/parent block))
            (:db/id (:block/page block)))            ; don't auto-save for page's properties block
         (get-in block [:block/properties :title]))
      (reset! *auto-save-timeout
              (js/setTimeout
               (fn []
                 (when (state/input-idle? repo)
                   (state/set-editor-op! :auto-save)
                   (save-current-block! {})
                   (state/set-editor-op! nil)))
               500)))))

(defn handle-last-input []
  (let [input           (state/get-input)
        pos             (cursor/pos input)
        last-input-char (util/nth-safe (.-value input) (dec pos))]

    ;; TODO: is it cross-browser compatible?
    ;; (not= (gobj/get native-e "inputType") "insertFromPaste")
    (when (= last-input-char (state/get-editor-command-trigger))
      (when (seq (get-matched-commands input))
        (reset! commands/*slash-caret-pos (cursor/get-caret-pos input))
        (reset! commands/*show-commands true)))

    (if (= last-input-char commands/angle-bracket)
      (when (seq (get-matched-block-commands input))
        (reset! commands/*angle-bracket-caret-pos (cursor/get-caret-pos input))
        (reset! commands/*show-block-commands true))
      nil)))

(defn block-on-chosen-handler
  [_input id q format]
  (fn [chosen _click?]
    (state/set-editor-show-block-search! false)
    (let [uuid-string (str (:block/uuid chosen))]

      ;; block reference
      (insert-command! id
                       (util/format "((%s))" uuid-string)
                       format
                       {:last-pattern (str "((" (if @*selected-text "" q))
                        :end-pattern "))"
                        :postfix-fn   (fn [s] (util/replace-first "))" s ""))
                        :forward-pos 3})

      ;; Save it so it'll be parsed correctly in the future
      (set-block-property! (:block/uuid chosen)
                           :id
                           uuid-string)

      (when-let [input (gdom/getElement id)]
        (.focus input)))))

(defn block-non-exist-handler
  [input]
  (fn []
    (state/set-editor-show-block-search! false)
    (cursor/move-cursor-forward input 2)))

(defn get-block-tree-insert-pos-after-target
  "return [target-block sibling? delete-editing-block? editing-block]"
  ([target-block-id sibling?]
   (get-block-tree-insert-pos-after-target target-block-id sibling? nil))
  ([target-block-id sibling? editing-block]
   (when-let [target-block (db/pull target-block-id)]
     [target-block sibling? false (or editing-block target-block)])))

(defn- get-block-tree-insert-pos-at-point
  "return [target-block sibling? delete-editing-block? editing-block]"
  []
  (when-let [editing-block (db/pull (:db/id (state/get-edit-block)))]
    (let [input (gdom/getElement (state/get-edit-input-id))
          pos (cursor/pos input)
          value (:value (get-state))
          [fst-block-text snd-block-text] (compute-fst-snd-block-text value pos)
          parent (:db/id (:block/parent editing-block))
          parent-block (db/pull parent)
          left (:db/id (:block/left editing-block))
          left-block (db/pull left)
          [_ _ config] (state/get-editor-args)
          block-id (:block/uuid editing-block)
          block-self? (block-self-alone-when-insert? config block-id)
          has-children? (db/has-children? (state/get-current-repo)
                                          (:block/uuid editing-block))
          collapsed? (util/collapsed? editing-block)]
      (conj (match (mapv boolean [(seq fst-block-text) (seq snd-block-text)
                                  block-self? has-children? (= parent left) collapsed?])
              ;; when zoom at editing-block
              [_ _ true _ _ _]
              [editing-block false false]

              ;; insert after editing-block
              [true _ false true _ false]
              [editing-block false false]
              [true _ false true _ true]
              [editing-block true false]
              [true _ false false _ _]
              [editing-block true false]
              [false false false true _ false]
              [editing-block false false]
              [false false false true _ true]
              [editing-block true false]
              [false false false false _ _]
              [editing-block true true]

              ;; insert before editing-block
              [false true false _ true _]
              [parent-block false false]
              [false true false _ false _]
              [left-block true false])
            editing-block))))

(defn- paste-block-tree-at-point-edit-aux
  [uuid page exclude-properties format content-update-fn]
  (fn [block]
    (outliner-core/block
     (let [new-content
           (if content-update-fn
             (content-update-fn (:block/content block))
             (:block/content block))
           new-content
           (->> new-content
                (property/remove-property format "id")
                (property/remove-property format "custom_id"))
           m (merge (dissoc block
                            :block/pre-block?
                            :block/uuid
                            :db/id
                            :block/left
                            :block/parent)
                    {:block/uuid uuid
                     :block/page (select-keys page [:db/id])
                     :block/format format
                     :block/properties (apply dissoc (:block/properties block)
                                         (concat [:id :custom_id :custom-id]
                                                 exclude-properties))
                     :block/meta (dissoc (:block/meta block) :start-pos :end-pos)
                     :block/content new-content
                     :block/path-refs (->> (cons (:db/id page) (:block/path-refs block))
                                           (remove nil?))})]
       m))))

(defn paste-block-vec-tree-at-target
  [tree exclude-properties {:keys [content-update-fn
                                   get-pos-fn
                                   page-block]
                            :as _opts}]
  (let [page (or page-block
                 (:block/page (db/entity (:db/id (state/get-edit-block)))))
        [target-block sibling? delete-editing-block? editing-block]
        ((or get-pos-fn get-block-tree-insert-pos-at-point))]
    (when target-block
      (let [target-block (outliner-core/block target-block)
            format (or (:block/format target-block) (state/get-preferred-format))
            new-block-uuids (atom #{})
            metadata-replaced-blocks
            (zip/root
             (loop [loc (zip/vector-zip tree)]
               (if (zip/end? loc)
                 loc
                 (if (vector? (zip/node loc))
                   (recur (zip/next loc))
                   (let [uuid (random-uuid)]
                     (swap! new-block-uuids (fn [acc uuid] (conj acc uuid)) uuid)
                     (recur (zip/next (zip/edit
                                       loc
                                       (paste-block-tree-at-point-edit-aux
                                        uuid page exclude-properties format content-update-fn)))))))))
            _ (when editing-block
                (let [editing-block (outliner-core/block editing-block)]
                  (outliner-core/save-node editing-block)))
            _ (outliner-core/insert-nodes metadata-replaced-blocks target-block sibling?)
            _ (when (and delete-editing-block? editing-block)
                (when-let [id (:db/id editing-block)]
                  (outliner-core/delete-node (outliner-core/block (db/pull id)) true)))]
        (last metadata-replaced-blocks)))))

(defn- tree->vec-tree
  "tree:
  [
  {
    :content 'this is a block',
    :properties {\"key\" \"value\" \"key2\" \"value2\"},
    :children [
      { :content 'this is child block' }
    ]
  },
  {
    :content 'this is sibling block'
  }
  ]"
  [tree]
  (into []
        (mapcat
         (fn [e]
           (let [e* (select-keys e [:content :properties])
                 children (:children e)]
             (if (seq children)
               [e* (tree->vec-tree (:children e))]
               [e*])))
         tree)))

(defn- vec-tree->vec-block-tree
  [tree format]
  (let [loc (zip/vector-zip tree)]
    (loop [loc loc]
      (if (zip/end? loc)
        (zip/root loc)
        (let [node (zip/node loc)]
          (if (vector? node)
            (recur (zip/next loc))
            (let [content (:content node)
                  props (into [] (:properties node))
                  content* (str (if (= :markdown format) "- " "* ")
                                (property/insert-properties format content props))
                  ast (mldoc/->edn content* (mldoc/default-config format))
                  blocks (block/extract-blocks ast content* true format)
                  fst-block (first blocks)]
              (assert fst-block "fst-block shouldn't be nil")
              (recur (zip/next (zip/replace loc fst-block))))))))))

(defn paste-block-tree-after-target
  [target-block-id sibling? tree format]
  (let [vec-tree (tree->vec-tree tree)
        block-tree (vec-tree->vec-block-tree vec-tree format)
        target-block (db/pull target-block-id)
        page-block (if (:block/name target-block) target-block
                       (db/entity (:db/id (:block/page (db/pull target-block-id)))))
        ;; sibling? = false, when target-block is a page-block
        sibling? (if (= target-block-id (:db/id page-block))
                   false
                   sibling?)]
    (paste-block-vec-tree-at-target
     block-tree []
     {:get-pos-fn #(get-block-tree-insert-pos-after-target target-block-id sibling?)
      :page-block page-block})))

(defn insert-template!
  ([element-id db-id]
   (insert-template! element-id db-id {}))
  ([element-id db-id opts]
   (when-let [db-id (if (integer? db-id)
                      db-id
                      (:db/id (db-model/get-template-by-name (name db-id))))]
     (let [repo (state/get-current-repo)
           block (db/entity db-id)
           format (:block/format block)
           block-uuid (:block/uuid block)
           template-including-parent? (not (false? (:template-including-parent (:block/properties block))))
           blocks (if template-including-parent? (db/get-block-and-children repo block-uuid) (db/get-block-children repo block-uuid))
           root-block (db/pull db-id)
           blocks-exclude-root (remove (fn [b] (= (:db/id b) db-id)) blocks)
           sorted-blocks (tree/sort-blocks blocks-exclude-root root-block)
           sorted-blocks (->> (blocks-with-level sorted-blocks)
                              (map second))
           result-blocks (if template-including-parent?
                           sorted-blocks
                           (->> (drop 1 sorted-blocks)
                                (map (fn [block] (update block :level dec)))))
           tree (blocks-vec->tree result-blocks)]
       (when element-id
         (insert-command! element-id "" format {}))
       (let [opts (merge
                   {:content-update-fn (fn [content]
                                         (->> content
                                              (property/remove-property format "template")
                                              (property/remove-property format "template-including-parent")
                                              template/resolve-dynamic-template!))}
                   opts)
             last-block (paste-block-vec-tree-at-target tree [:id :template :template-including-parent] opts)]
         (clear-when-saved!)
         (let [block (if (tree/satisfied-inode? last-block)
                       (:data last-block)
                       (:data (last (flatten last-block))))]
           (edit-block! block :max (:block/uuid block))))))))

(defn template-on-chosen-handler
  [element-id]
  (fn [[_template db-id] _click?]
    (insert-template! element-id db-id)))

(defn parent-is-page?
  [{{:block/keys [parent page]} :data :as node}]
  {:pre [(tree/satisfied-inode? node)]}
  (= parent page))

(defn outdent-on-enter
  [node]
  (when-not (parent-is-page? node)
    (let [parent-node (tree/-get-parent node)]
      (outliner-core/move-subtree node parent-node true))))

(defn- last-top-level-child?
  [{:keys [id]} current-node]
  (when id
    (when-let [entity (if (util/uuid-string? (str id))
                        (db/entity [:block/uuid (uuid id)])
                        (db/entity [:block/name (util/page-name-sanity-lc id)]))]
      (= (:block/uuid entity) (tree/-get-parent-id current-node)))))

(defn- insert
  [insertion]
  (when-not (auto-complete?)
    (let [^js input (state/get-input)
          selected-start (util/get-selection-start input)
          selected-end (util/get-selection-end input)
          value (.-value input)
          s1 (subs value 0 selected-start)
          s2 (subs value selected-end)]
      (state/set-edit-content! (state/get-edit-input-id)
                               (str s1 insertion s2))
      (cursor/move-cursor-to input (+ selected-start (count insertion))))))

(defn- keydown-new-line
  []
  (insert "\n"))

(declare delete-and-update)

(defn- dwim-in-properties
  [state]
  (when-not (auto-complete?)
    (let [{:keys [block]} (get-state)]
      (when block
        (let [input (state/get-input)
              content (gobj/get input "value")
              format (:block/format (:block (get-state)))
              property-key (:raw-content (thingatpt/property-key-at-point input))
              org? (= format :org)
              move-to-pos (if org? 2 3)]
          (if org?
            (cond
              (and property-key (not= property-key ""))
              (case property-key
                ;; When cursor in "PROPERTIES", add :|: in a new line and move cursor to |
                "PROPERTIES"
                (do (cursor/move-cursor-to-line-end input)
                    (insert "\n:: ")
                    (cursor/move-cursor-backward input move-to-pos))
                ;; When cursor in "END", new block (respect the previous enter behavior)
                "END"
                (do
                  (cursor/move-cursor-to-end input)
                  (insert-new-block! state))
                ;; cursor in other positions of :ke|y: or ke|y::, move to line end for inserting value.
                (if (property/property-key-exist? format content property-key)
                  (notification/show!
                   [:p.content
                    (util/format "Property key \"%s\" already exists!" property-key)]
                   :error)
                  (cursor/move-cursor-to-line-end input)))

              ;; when cursor in empty property key
              (and property-key (= property-key ""))
              (do (delete-and-update
                   input
                   (cursor/line-beginning-pos input)
                   (inc (cursor/line-end-pos input)))
                  (property/goto-properties-end format input)
                  (cursor/move-cursor-to-line-end input))
              :else
              ;;When cursor in other place of PROPERTIES drawer, add :|: in a new line and move cursor to |
              (do
                (insert "\n:: ")
                (cursor/move-cursor-backward input move-to-pos)))
            (insert "\n")))))))

(defn- dwim-in-list
  [_state]
  (when-not (auto-complete?)
    (let [{:keys [block]} (get-state)]
      (when block
        (let [input (state/get-input)]
          (when-let [item (thingatpt/list-item-at-point input)]
            (let [{:keys [full-content indent bullet checkbox ordered _]} item
                  next-bullet (if ordered (str (inc bullet) ".") bullet)
                  checkbox (when checkbox "[ ] ")]
              (if (= (count full-content)
                     (+ (if ordered (+ (count (str bullet)) 2) 2) (when checkbox (count checkbox))))
                (delete-and-update input (cursor/line-beginning-pos input) (cursor/line-end-pos input))
                (do (cursor/move-cursor-to-line-end input)
                    (insert (str "\n" indent next-bullet " " checkbox))
                    (when ordered
                      (let [bullet-atom (atom (inc bullet))]
                        (while (when-let [next-item (list/get-next-item input)]
                                 (swap! bullet-atom inc)
                                 (let [{:keys [full-content start end]} next-item
                                       new-bullet @bullet-atom]
                                   (delete-and-update input start end)
                                   (insert (string/replace-first full-content (:bullet next-item) new-bullet))
                                   true))
                          nil)
                        (cursor/move-cursor-to input (+ (:end item) (count next-bullet) 2)))))))))))))

(defn toggle-list!
  []
  (when-not (auto-complete?)
    (let [{:keys [block]} (get-state)]
      (when block
        (let [input (state/get-input)
              format (or (db/get-page-format (state/get-current-page)) (state/get-preferred-format))
              new-unordered-bullet (case format :org "-" "*")
              current-pos (cursor/pos input)
              content (state/get-edit-content)
              pos (atom current-pos)]
          (if-let [item (thingatpt/list-item-at-point input)]
            (let [{:keys [ordered]} item
                  list-beginning-pos (list/list-beginning-pos input)
                  list-end-pos (list/list-end-pos input)
                  list (subs content list-beginning-pos list-end-pos)
                  items (string/split-lines list)
                  splitter-reg (if ordered #"[\d]*\.\s*" #"[-\*]{1}\s*")
                  items-without-bullet (vec (map #(last (string/split % splitter-reg 2)) items))
                  new-list (string/join "\n"
                                        (if ordered
                                          (map #(str new-unordered-bullet " " %) items-without-bullet)
                                          (map-indexed #(str (inc %1) ". " %2) items-without-bullet)))
                  index-of-current-item (inc (.indexOf items-without-bullet
                                                       (last (string/split (:raw-content item) splitter-reg 2))))
                  numbers-length (->> (map-indexed
                                       #_:clj-kondo/ignore
                                       #(str (inc %1) ". ")
                                       (subvec items-without-bullet 0 index-of-current-item))
                                      string/join
                                      count)
                  pos-diff (- numbers-length (* 2 index-of-current-item))]
              (delete-and-update input list-beginning-pos list-end-pos)
              (insert new-list)
              (reset! pos (if ordered
                            (- current-pos pos-diff)
                            (+ current-pos pos-diff))))
            (let [prev-item (list/get-prev-item input)]
              (cursor/move-cursor-down input)
              (cursor/move-cursor-to-line-beginning input)
              (if prev-item
                (let [{:keys [bullet ordered]} prev-item
                      current-bullet (if ordered (str (inc bullet) ".") bullet)]
                  (insert (str current-bullet " "))
                  (reset! pos (+ current-pos (count current-bullet) 1)))
                (do (insert (str new-unordered-bullet " "))
                    (reset! pos (+ current-pos 2))))))
          (cursor/move-cursor-to input @pos))))))

(defn toggle-page-reference-embed
  [parent-id]
  (let [{:keys [block]} (get-state)]
    (when block
      (let [input (state/get-input)
            new-pos (cursor/get-caret-pos input)
            page-ref-fn (fn [bounds backward-pos]
                          (commands/simple-insert!
                           parent-id bounds
                           {:backward-pos backward-pos
                            :check-fn (fn [_ _ _]
                                        (reset! commands/*slash-caret-pos new-pos)
                                        (commands/handle-step [:editor/search-page]))}))]
        (state/set-editor-show-page-search! false)
        (let [selection (get-selection-and-format)
              {:keys [selection-start selection-end selection]} selection]
          (if selection
            (do (delete-and-update input selection-start selection-end)
                (insert (util/format "[[%s]]" selection)))
            (if-let [embed-ref (thingatpt/embed-macro-at-point input)]
              (let [{:keys [raw-content start end]} embed-ref]
                (delete-and-update input start end)
                (if (= 5 (count raw-content))
                  (page-ref-fn "[[]]" 2)
                  (insert raw-content)))
              (if-let [page-ref (thingatpt/page-ref-at-point input)]
                (let [{:keys [start end full-content raw-content]} page-ref]
                  (delete-and-update input start end)
                  (if (= raw-content "")
                    (page-ref-fn "{{embed [[]]}}" 4)
                    (insert (util/format "{{embed %s}}" full-content))))
                (page-ref-fn "[[]]" 2)))))))))

(defn toggle-block-reference-embed
  [parent-id]
  (let [{:keys [block]} (get-state)]
    (when block
      (let [input (state/get-input)
            new-pos (cursor/get-caret-pos input)
            block-ref-fn (fn [bounds backward-pos]
                           (commands/simple-insert!
                            parent-id bounds
                            {:backward-pos backward-pos
                             :check-fn     (fn [_ _ _]
                                             (reset! commands/*slash-caret-pos new-pos)
                                             (commands/handle-step [:editor/search-block]))}))]
        (state/set-editor-show-block-search! false)
        (if-let [embed-ref (thingatpt/embed-macro-at-point input)]
          (let [{:keys [raw-content start end]} embed-ref]
            (delete-and-update input start end)
            (if (= 5 (count raw-content))
              (block-ref-fn "(())" 2)
              (insert raw-content)))
          (if-let [page-ref (thingatpt/block-ref-at-point input)]
            (let [{:keys [start end full-content raw-content]} page-ref]
              (delete-and-update input start end)
              (if (= raw-content "")
                (block-ref-fn "{{embed (())}}" 4)
                (insert (util/format "{{embed %s}}" full-content))))
            (block-ref-fn "(())" 2)))))))

(defn- keydown-new-block
  [state]
  (when-not (auto-complete?)
    (let [{:keys [block config]} (get-state)]
      (when block
        (let [input (state/get-input)
              content (gobj/get input "value")
              pos (cursor/pos input)
              current-node (outliner-core/block block)
              has-right? (-> (tree/-get-right current-node)
                             (tree/satisfied-inode?))
              thing-at-point ;intern is not supported in cljs, need a more elegant solution
              (or (when (thingatpt/get-setting :admonition&src?)
                    (thingatpt/admonition&src-at-point input))
                  (when (thingatpt/get-setting :markup?)
                    (thingatpt/markup-at-point input))
                  (when (thingatpt/get-setting :block-ref?)
                    (thingatpt/block-ref-at-point input))
                  (when (thingatpt/get-setting :page-ref?)
                    (thingatpt/page-ref-at-point input))
                  (when (thingatpt/get-setting :properties?)
                    (thingatpt/properties-at-point input))
                  (when (thingatpt/get-setting :list?)
                    (and (not (cursor/beginning-of-line? input))
                         (thingatpt/list-item-at-point input))))]
          (cond
            thing-at-point
            (case (:type thing-at-point)
              "markup" (let [right-bound (:bounds thing-at-point)]
                         (cursor/move-cursor-to
                          input
                          (+ (string/index-of content right-bound pos)
                             (count right-bound))))
              "admonition-block" (keydown-new-line)
              "source-block" (do
                               (keydown-new-line)
                               (case (:action thing-at-point)
                                 :into-code-editor
                                 (state/into-code-editor-mode!)
                                 nil))
              "block-ref" (open-block-in-sidebar! (:link thing-at-point))
              "page-ref" (when-not (string/blank? (:link thing-at-point))
                           (let [page (:link thing-at-point)
                                 page-name (db-model/get-redirect-page-name page)]
                             (insert-first-page-block-if-not-exists! page-name)
                             (route-handler/redirect-to-page! page-name)))
              "list-item" (dwim-in-list state)
              "properties-drawer" (dwim-in-properties state))

            (and
             (string/blank? content)
             (not has-right?)
             (not (last-top-level-child? config current-node)))
            (outdent-on-enter current-node)

            :else
            (profile
             "Insert block"
             (insert-new-block! state))))))))

(defn keydown-new-block-handler [state e]
  (if (state/doc-mode-enter-for-new-line?)
    (keydown-new-line)
    (do
      (.preventDefault e)
      (keydown-new-block state))))

(defn keydown-new-line-handler [state e]
  (if (state/doc-mode-enter-for-new-line?)
    (keydown-new-block state)
    (do
      (.preventDefault e)
      (keydown-new-line))))

(defn- select-first-last
  "Select first or last block in viewpoint"
  [direction]
  (let [f (case direction :up last :down first)
        block (->> (util/get-blocks-noncollapse)
                   (f))]
    (when block
      (.scrollIntoView block #js {:behavior "smooth" :block "center"})
      (state/exit-editing-and-set-selected-blocks! [block]))))

(defn- select-up-down [direction]
  (let [selected (first (state/get-selection-blocks))
        f (case direction
            :up util/get-prev-block-non-collapsed
            :down util/get-next-block-non-collapsed)
        sibling-block (f selected)]
    (when (and sibling-block (dom/attr sibling-block "blockid"))
      (.scrollIntoView sibling-block #js {:behavior "smooth" :block "center"})
      (state/exit-editing-and-set-selected-blocks! [sibling-block]))))

(defn- move-cross-boundrary-up-down
  [direction]
  (let [input (state/get-input)
        line-pos (util/get-first-or-last-line-pos input)
        repo (state/get-current-repo)
        f (case direction
            :up util/get-prev-block-non-collapsed
            :down util/get-next-block-non-collapsed)
        sibling-block (f (gdom/getElement (state/get-editing-block-dom-id)))
        {:block/keys [uuid content format]} (state/get-edit-block)]
    (when sibling-block
      (when-let [sibling-block-id (dom/attr sibling-block "blockid")]
        (let [value (state/get-edit-content)]
          (when (not= (clean-content! format content)
                      (string/trim value))
            (save-block! repo uuid value)))

        (let [new-id (string/replace (gobj/get sibling-block "id") "ls-block" "edit-block")
              new-uuid (cljs.core/uuid sibling-block-id)
              block (db/pull repo '[*] [:block/uuid new-uuid])]
          (edit-block! block
                       [direction line-pos]
                       new-id))))))

(defn keydown-up-down-handler
  [direction]
  (let [input (state/get-input)
        selected-start (util/get-selection-start input)
        selected-end (util/get-selection-end input)
        up? (= direction :up)
        down? (= direction :down)]
    (cond
      (not= selected-start selected-end)
      (if up?
        (cursor/move-cursor-to input selected-start)
        (cursor/move-cursor-to input selected-end))

      (or (and up? (cursor/textarea-cursor-first-row? input))
          (and down? (cursor/textarea-cursor-last-row? input)))
      (move-cross-boundrary-up-down direction)

      :else
      (if up?
        (cursor/move-cursor-up input)
        (cursor/move-cursor-down input)))))

(defn- move-to-block-when-cross-boundrary
  [direction]
  (let [up? (= :left direction)
        pos (if up? :max 0)
        {:block/keys [format uuid] :as block} (state/get-edit-block)
        id (state/get-edit-input-id)
        repo (state/get-current-repo)
        f (if up? util/get-prev-block-non-collapsed util/get-next-block-non-collapsed)
        sibling-block (f (gdom/getElement (state/get-editing-block-dom-id)))]
    (when sibling-block
      (when-let [sibling-block-id (dom/attr sibling-block "blockid")]
        (let [content (:block/content block)
              value (state/get-edit-content)]
          (when (not= (clean-content! format content)
                      (string/trim value))
            (save-block! repo uuid value)))
        (let [block (db/pull repo '[*] [:block/uuid (cljs.core/uuid sibling-block-id)])]
          (edit-block! block pos id))))))

(defn keydown-arrow-handler
  [direction]
  (let [input (state/get-input)
        element js/document.activeElement
        selected-start (util/get-selection-start input)
        selected-end (util/get-selection-end input)
        left? (= direction :left)
        right? (= direction :right)]
    (when (= input element)
      (cond
        (not= selected-start selected-end)
        (if left?
          (cursor/move-cursor-to input selected-start)
          (cursor/move-cursor-to input selected-end))

        (or (and left? (cursor/start? input))
            (and right? (cursor/end? input)))
        (move-to-block-when-cross-boundrary direction)

        :else
        (if left?
          (cursor/move-cursor-backward input)
          (cursor/move-cursor-forward input))))))

(defn- delete-and-update [^js input start end]
  (util/safe-set-range-text! input "" start end)
  (state/set-edit-content! (state/get-edit-input-id) (.-value input)))

(defn- delete-concat [current-block]
  (let [input-id (state/get-edit-input-id)
        ^js input (state/get-input)
        current-pos (cursor/pos input)
        value (gobj/get input "value")
        repo (state/get-current-repo)
        right (outliner-core/get-right-node (outliner-core/block current-block))
        current-block-has-children? (db/has-children? repo (:block/uuid current-block))
        collapsed? (util/collapsed? current-block)
        first-child (:data (tree/-get-down (outliner-core/block current-block)))
        next-block (if (or collapsed? (not current-block-has-children?))
                     (:data right)
                     first-child)]
    (cond
      (and collapsed? right (db/has-children? repo (tree/-get-id right)))
      nil

      (and (not collapsed?) first-child (db/has-children? repo (:block/uuid first-child)))
      nil

      :else
      (do
        (delete-block-aux! next-block false)
        (state/set-edit-content! input-id (str value "" (:block/content next-block)))
        (cursor/move-cursor-to input current-pos)))))

(defn keydown-delete-handler
  [_e]
  (let [^js input (state/get-input)
        current-pos (cursor/pos input)
        value (gobj/get input "value")
        end? (= current-pos (count value))
        current-block (state/get-edit-block)
        selected-start (util/get-selection-start input)
        selected-end (util/get-selection-end input)]
    (when current-block
      (cond
        (not= selected-start selected-end)
        (delete-and-update input selected-start selected-end)

        (and end? current-block)
        (delete-concat current-block)

        :else
        (delete-and-update input current-pos (inc current-pos))))))

(defn keydown-backspace-handler
  [cut? e]
  (let [^js input (state/get-input)
        id (state/get-edit-input-id)
        current-pos (cursor/pos input)
        value (gobj/get input "value")
        deleted (and (> current-pos 0)
                     (util/nth-safe value (dec current-pos)))
        selected-start (util/get-selection-start input)
        selected-end (util/get-selection-end input)
        block-id (:block/uuid (state/get-edit-block))
        page (state/get-current-page)
        repo (state/get-current-repo)]
    (mark-last-input-time! repo)
    (cond
      (not= selected-start selected-end)
      (do
        (util/stop e)
        (when cut?
          (js/document.execCommand "copy"))
        (delete-and-update input selected-start selected-end))

      (and (zero? current-pos)
           ;; not the top block in a block page
           (not (and page
                     (util/uuid-string? page)
                     (= (medley/uuid page) block-id))))
      (do
        (util/stop e)
        (delete-block! repo false))

      (and (> current-pos 1)
           (= (util/nth-safe value (dec current-pos)) (state/get-editor-command-trigger)))
      (do
        (util/stop e)
        (reset! *slash-caret-pos nil)
        (reset! *show-commands false)
        (delete-and-update input (dec current-pos) current-pos))

      (and (> current-pos 1)
           (= (util/nth-safe value (dec current-pos)) commands/angle-bracket))
      (do
        (util/stop e)
        (reset! *angle-bracket-caret-pos nil)
        (reset! *show-block-commands false)
        (delete-and-update input (dec current-pos) current-pos))

      ;; pair
      (and
       deleted
       (contains?
        (set (keys delete-map))
        deleted)
       (>= (count value) (inc current-pos))
       (= (util/nth-safe value current-pos)
          (get delete-map deleted)))

      (do
        (util/stop e)
        (commands/delete-pair! id)
        (cond
          (and (= deleted "[") (state/get-editor-show-page-search?))
          (state/set-editor-show-page-search! false)

          (and (= deleted "(") (state/get-editor-show-block-search?))
          (state/set-editor-show-block-search! false)

          :else
          nil))

      ;; deleting hashtag
      (and (= deleted "#") (state/get-editor-show-page-search-hashtag?))
      (do
        (state/set-editor-show-page-search-hashtag! false)
        (delete-and-update input (dec current-pos) current-pos))

      ;; just delete
      :else
      (do
        (util/stop e)
        (delete-and-update
         input (util/safe-dec-current-pos-from-end (.-value input) current-pos) current-pos)))))

(defn indent-outdent
  [indent?]
  (state/set-editor-op! :indent-outdent)
  (let [{:keys [block]} (get-state)]
    (when block
      (let [current-node (outliner-core/block block)]
        (outliner-core/indent-outdent-nodes [current-node] indent?)))
    (state/set-editor-op! :nil)))

(defn keydown-tab-handler
  [direction]
  (fn [e]
    (cond
      (state/editing?)
      (let [input (state/get-input)
            pos (cursor/pos input)]
        (when (and (not (state/get-editor-show-input))
                   (not (state/get-editor-show-date-picker?))
                   (not (state/get-editor-show-template-search?)))
          (util/stop e)
          (indent-outdent (not (= :left direction)))
          (and input pos
               (when-let [input (state/get-input)]
                 (cursor/move-cursor-to input pos)))))

      (state/selection?)
      (do
        (util/stop e)
        (on-tab direction)))
    nil))

(defn keydown-not-matched-handler
  [format]
  (fn [e _key-code]
    (let [input-id (state/get-edit-input-id)
          input (state/get-input)
          key (gobj/get e "key")
          value (gobj/get input "value")
          ctrlKey (gobj/get e "ctrlKey")
          metaKey (gobj/get e "metaKey")
          pos (cursor/pos input)
          hashtag? (or (surround-by? input "#" " ")
                       (surround-by? input "#" :end)
                       (= key "#"))]
      (cond
        (and (util/event-is-composing? e true) ;; #3218
             (not hashtag?) ;; #3283 @Rime
             (not (state/get-editor-show-page-search-hashtag?))) ;; #3283 @MacOS pinyin
        nil

        (or ctrlKey metaKey)
        nil

        ;; FIXME: On iOS, a backspace click to call keydown-backspace-handler
        ;; does not work sometimes in an empty block, hence the empty block
        ;; can't be deleted. Need to figure out why and find a better solution.
        (and (mobile/native-ios?)
             (= key "Backspace")
             (= value ""))
        (do
          (util/stop e)
          (delete-block! (state/get-current-repo) false))

        (and (= key "#")
             (and
              (> pos 0)
              (= "#" (util/nth-safe value (dec pos)))))
        (state/set-editor-show-page-search-hashtag! false)

        (and
         (contains? (set/difference (set (keys reversed-autopair-map))
                                    #{"`"})
                    key)
         (= (get-current-input-char input) key))
        (do
          (util/stop e)
          (cursor/move-cursor-forward input))

        (and (autopair-when-selected key) (string/blank? (util/get-selected-text)))
        nil

        (and (not (string/blank? (util/get-selected-text)))
             (contains? keycode/left-square-brackets-keys key))
        (do
          (autopair input-id "[" format nil)
          (util/stop e))

        (and (not (string/blank? (util/get-selected-text)))
             (contains? keycode/left-paren-keys key))
        (do
          (util/stop e)
          (autopair input-id "(" format nil))

        (contains? (set (keys autopair-map)) key)
        (do
          (util/stop e)
          (autopair input-id key format nil))

        hashtag?
        (do
          (commands/handle-step [:editor/search-page-hashtag])
          (if (= key "#")
            (state/set-last-pos! (inc (cursor/pos input))) ;; In keydown handler, the `#` is not inserted yet.
            (state/set-last-pos! (cursor/pos input)))
          (reset! commands/*slash-caret-pos (cursor/get-caret-pos input)))

        (let [sym "$"]
          (and (= key sym)
               (>= (count value) 1)
               (> pos 0)
               (= (nth value (dec pos)) sym)
               (if (> (count value) pos)
                 (not= (nth value pos) sym)
                 true)))
        (commands/simple-insert! input-id "$$" {:backward-pos 2})

        (let [sym "^"]
          (and (= key sym)
               (>= (count value) 1)
               (> pos 0)
               (= (nth value (dec pos)) sym)
               (if (> (count value) pos)
                 (not= (nth value pos) sym)
                 true)))
        (commands/simple-insert! input-id "^^" {:backward-pos 2})

        :else
        nil))))

(defn ^:large-vars/cleanup-todo keyup-handler
  [_state input input-id search-timeout]
  (fn [e key-code]
    (when-not (util/event-is-composing? e)
      (let [k (gobj/get e "key")
            code (gobj/getValueByKeys e "event_" "code")
            format (:format (get-state))
            current-pos (cursor/pos input)
            value (gobj/get input "value")
            c (util/nth-safe value (dec current-pos))
            last-key-code (state/get-last-key-code)
            blank-selected? (string/blank? (util/get-selected-text))
            is-processed? (util/event-is-composing? e true) ;; #3440
            non-enter-processed? (and is-processed? ;; #3251
                                      (not= code keycode/enter-code))] ;; #3459
        (when-not (or (state/get-editor-show-input) non-enter-processed?)
          (cond
            (and (not (contains? #{"ArrowDown" "ArrowLeft" "ArrowRight" "ArrowUp"} k))
                 (not (:editor/show-page-search? @state/state))
                 (not (:editor/show-page-search-hashtag? @state/state))
                 (wrapped-by? input "[[" "]]"))
            (let [orig-pos (cursor/get-caret-pos input)
                  value (gobj/get input "value")
                  square-pos (string/last-index-of (subs value 0 (:pos orig-pos)) "[[")
                  pos (+ square-pos 2)
                  _ (state/set-last-pos! pos)
                  pos (assoc orig-pos :pos pos)
                  command-step (if (= \# (util/nth-safe value (dec square-pos)))
                                 :editor/search-page-hashtag
                                 :editor/search-page)]
              (commands/handle-step [command-step])
              (reset! commands/*slash-caret-pos pos))

            (and blank-selected?
                 (contains? keycode/left-square-brackets-keys k)
                 (= (:key last-key-code) k)
                 (> current-pos 0)
                 (not (wrapped-by? input "[[" "]]")))
            (do
              (commands/handle-step [:editor/input "[[]]" {:backward-truncate-number 2
                                                           :backward-pos 2}])
              (commands/handle-step [:editor/search-page])
              (reset! commands/*slash-caret-pos (cursor/get-caret-pos input)))

            (and blank-selected?
                 (contains? keycode/left-paren-keys k)
                 (= (:key last-key-code) k)
                 (> current-pos 0)
                 (not (wrapped-by? input "((" "))")))
            (do
              (commands/handle-step [:editor/input "(())" {:backward-truncate-number 2
                                                           :backward-pos 2}])
              (commands/handle-step [:editor/search-block :reference])
              (reset! commands/*slash-caret-pos (cursor/get-caret-pos input)))

            (and (= "〈" c)
                 (= "《" (util/nth-safe value (dec (dec current-pos))))
                 (> current-pos 0))
            (do
              (commands/handle-step [:editor/input commands/angle-bracket {:last-pattern "《〈"
                                                                           :backward-pos 0}])
              (reset! commands/*angle-bracket-caret-pos (cursor/get-caret-pos input))
              (reset! commands/*show-block-commands true))

            (and (= c " ")
                 (or (= (util/nth-safe value (dec (dec current-pos))) "#")
                     (not (state/get-editor-show-page-search?))
                     (and (state/get-editor-show-page-search?)
                          (not= (util/nth-safe value current-pos) "]"))))
            (state/set-editor-show-page-search-hashtag! false)

            (and @*show-commands (not= k (state/get-editor-command-trigger)))
            (let [matched-commands (get-matched-commands input)]
              (if (seq matched-commands)
                (do
                  (reset! *show-commands true)
                  (reset! commands/*matched-commands matched-commands))
                (reset! *show-commands false)))

            (and @*show-block-commands (not= key-code 188)) ; not <
            (let [matched-block-commands (get-matched-block-commands input)]
              (if (seq matched-block-commands)
                (cond
                  (= key-code 9)       ;tab
                  (when @*show-block-commands
                    (util/stop e)
                    (insert-command! input-id
                                     (last (first matched-block-commands))
                                     format
                                     {:last-pattern commands/angle-bracket}))

                  :else
                  (reset! commands/*matched-block-commands matched-block-commands))
                (reset! *show-block-commands false)))

            (nil? @search-timeout)
            (close-autocomplete-if-outside input)

            :else
            nil))
        (when-not (or (= k "Shift") is-processed?)
          (state/set-last-key-code! {:key-code key-code
                                     :code code
                                     :key k
                                     :shift? (.-shiftKey e)}))))))


(defn editor-on-click!
  [id]
  (fn [_e]
    (let [input (gdom/getElement id)]
      (close-autocomplete-if-outside input))))

(defonce mobile-toolbar-height 40)
(defn editor-on-height-change!
  [id]
  (fn [box-height ^js row-height]
    (let [row-height (:rowHeight (js->clj row-height :keywordize-keys true))
          input (gdom/getElement id)
          caret (cursor/get-caret-pos input)
          cursor-bottom (if caret (+ row-height (:top caret)) box-height)
          box-top (gobj/get (.getBoundingClientRect input) "top")
          cursor-y (+ cursor-bottom box-top)
          vw-height (.-height js/window.visualViewport)]
      (when (<  vw-height (+ cursor-y mobile-toolbar-height))
        (let [main-node (gdom/getElement "main-content-container")
              scroll-top (.-scrollTop main-node)]
          (set! (.-scrollTop main-node) (+ scroll-top (/ vw-height 2))))))))

(defn editor-on-change!
  [block id search-timeout]
  (fn [e]
    (if (state/sub :editor/show-block-search?)
      (let [timeout 300]
        (when @search-timeout
          (js/clearTimeout @search-timeout))
        (reset! search-timeout
                (js/setTimeout
                 #(edit-box-on-change! e block id)
                 timeout)))
      (edit-box-on-change! e block id))))

(defn blocks->tree-by-level
  [blocks]
  (let [min-level (apply min (mapv :block/level blocks))
        prefix-level (if (> min-level 1) (- min-level 1) 0)]
    (->> blocks
         (mapv #(assoc % :level (- (:block/level %) prefix-level)))
         (blocks-vec->tree))))

(defn- paste-text-parseable
  [format text]
  (let [tree (->>
              (block/extract-blocks
               (mldoc/->edn text (mldoc/default-config format)) text true format))
        min-level (apply min (mapv :block/level tree))
        prefix-level (if (> min-level 1) (- min-level 1) 0)
        tree* (->> tree
                   (mapv #(assoc % :level (- (:block/level %) prefix-level)))
                   (blocks-vec->tree))]
    (paste-block-vec-tree-at-target tree* [] nil)))

(defn- paste-segmented-text
  [format text]
  (let [paragraphs (string/split text #"(?:\r?\n){2,}")
        updated-paragraphs
        (string/join "\n"
                     (mapv (fn [p] (->> (string/trim p)
                                        ((fn [p]
                                           (if (util/safe-re-find (if (= format :org)
                                                                    #"\s*\*+\s+"
                                                                    #"\s*-\s+") p)
                                             p
                                             (str (if (= format :org) "* " "- ") p))))))
                           paragraphs))]
    (paste-text-parseable format updated-paragraphs)))

(defn- paste-text
  [text e]
  (let [copied-blocks (state/get-copied-blocks)
        copied-block-tree (:copy/block-tree copied-blocks)
        input (state/get-input)
        *stop-event? (atom true)]
    (cond
      (and
       (:copy/content copied-blocks)
       (not (string/blank? text))
       (= (string/replace (string/trim text) "\r" "")
          (string/replace (string/trim (:copy/content copied-blocks)) "\r" "")))
      (paste-block-vec-tree-at-target copied-block-tree [] nil)

      (and (util/url? text)
           (not (string/blank? (util/get-selected-text))))
      (html-link-format! text)

      (and (util/url? text)
           (or (string/includes? text "youtube.com")
               (string/includes? text "youtu.be"))
           (mobile/is-native-platform?))
      (commands/simple-insert! (state/get-edit-input-id) (util/format "{{youtube %s}}" text) nil)

      (and (util/url? text)
           (string/includes? text "twitter.com")
           (mobile/is-native-platform?))
      (commands/simple-insert! (state/get-edit-input-id) (util/format "{{twitter %s}}" text) nil)

      (and (text/block-ref? text)
           (wrapped-by? input "((" "))"))
      (commands/simple-insert! (state/get-edit-input-id) (text/get-block-ref text) nil)

      :else
      ;; from external
      (let [format (or (db/get-page-format (state/get-current-page)) :markdown)]
        (match [format
                (nil? (util/safe-re-find #"(?m)^\s*(?:[-+*]|#+)\s+" text))
                (nil? (util/safe-re-find #"(?m)^\s*\*+\s+" text))
                (nil? (util/safe-re-find #"(?:\r?\n){2,}" text))]
          [:markdown false _ _]
          (paste-text-parseable format text)

          [:org _ false _]
          (paste-text-parseable format text)

          [:markdown true _ false]
          (paste-segmented-text format text)

          [:markdown true _ true]
          (reset! *stop-event? false)

          [:org _ true false]
          (paste-segmented-text format text)

          [:org _ true true]
          (reset! *stop-event? false))))
    (when @*stop-event?
      (util/stop e))))

(defn paste-text-in-one-block-at-point
  []
  (utils/getClipText
   (fn [clipboard-data]
     (when-let [_ (state/get-input)]
       (state/append-current-edit-content! clipboard-data)))
   (fn [error]
     (js/console.error error))))

(defn editor-on-paste!
  [id]
  (fn [e]
    (state/set-state! :editor/on-paste? true)
    (let [text (.getData (gobj/get e "clipboardData") "text")
          input (state/get-input)]
      (if-not (string/blank? text)
        (if (thingatpt/org-admonition&src-at-point input)
          (do (util/stop e)
              (paste-text-in-one-block-at-point))
          (paste-text text e))
        (let [_handled
              (let [clipboard-data (gobj/get e "clipboardData")
                    files (.-files clipboard-data)]
                (when-let [file (first files)]
                  (when-let [block (state/get-edit-block)]
                    (upload-asset id #js[file] (:block/format block) *asset-uploading? true))))]
          (util/stop e))))))

(defn- cut-blocks-and-clear-selections!
  [copy?]
  (cut-selection-blocks copy?)
  (clear-selection!))

(defn shortcut-copy-selection
  [_e]
  (copy-selection-blocks))

(defn shortcut-cut-selection
  [e]
  (util/stop e)
  (cut-blocks-and-clear-selections! true))

(defn shortcut-delete-selection
  [e]
  (util/stop e)
  (cut-blocks-and-clear-selections! false))

;; credits to @pengx17
(defn- copy-current-block-ref
  []
  (when-let [current-block (state/get-edit-block)]
    (when-let [block-id (:block/uuid current-block)]
      (copy-block-ref! block-id #(str "((" % "))"))
      (notification/show!
       [:div
        [:span.mb-1.5 "Block ref copied!"]
        [:div [:code.whitespace-nowrap (str "((" block-id "))")]]]
       :success true
       ;; use uuid to make sure there is only one toast a time
       (str "copied-block-ref:" block-id)))))

(defn shortcut-copy
  "shortcut copy action:
  * when in selection mode, copy selected blocks
  * when in edit mode but no text selected, copy current block ref
  * when in edit mode with text selected, copy selected text as normal"
  [e]
  (when-not (auto-complete?)
    (cond
      (state/selection?)
      (shortcut-copy-selection e)

      (state/editing?)
      (let [input (state/get-input)
            selected-start (util/get-selection-start input)
            selected-end (util/get-selection-end input)]
        (if (= selected-start selected-end)
          (copy-current-block-ref)
          (js/document.execCommand "copy")))

      :else
      (js/document.execCommand "copy"))))

(defn shortcut-cut
  "shortcut cut action:
  * when in selection mode, cut selected blocks
  * when in edit mode with text selected, cut selected text
  * otherwise same as delete shortcut"
  [e]
  (cond
    (state/selection?)
    (shortcut-cut-selection e)

    (state/editing?)
    (keydown-backspace-handler true e)))

(defn delete-selection
  [e]
  (when (state/selection?)
    (shortcut-delete-selection e)))

(defn editor-delete
  [_state e]
  (when (state/editing?)
    (util/stop e)
    (keydown-delete-handler e)))

(defn editor-backspace
  [_state e]
  (when (state/editing?)
    (keydown-backspace-handler false e)))

(defn shortcut-up-down [direction]
  (fn [e]
    (when-not (auto-complete?)
      (util/stop e)
      (cond
        (state/editing?)
        (keydown-up-down-handler direction)

        (and (state/selection?) (== 1 (count (state/get-selection-blocks))))
        (select-up-down direction)

        :else
        (select-first-last direction)))))

(defn open-selected-block!
  [direction e]
  (when-let [block-id (some-> (state/get-selection-blocks)
                              first
                              (dom/attr "blockid")
                              medley/uuid)]
    (util/stop e)
    (let [block    {:block/uuid block-id}
          block-id (-> (state/get-selection-blocks)
                       first
                       (gobj/get "id")
                       (string/replace "ls-block" "edit-block"))
          left?    (= direction :left)]
      (edit-block! block
                   (if left? 0 :max)
                   block-id))))

(defn shortcut-left-right [direction]
  (fn [e]
    (when-not (auto-complete?)
      (cond
        (state/editing?)
        (do
          (util/stop e)
          (keydown-arrow-handler direction))

        (and (state/selection?) (== 1 (count (state/get-selection-blocks))))
        (do
          (util/stop e)
          (open-selected-block! direction e))

        :else
        nil))))

(defn clear-block-content! []
  (save-current-block! {:force? true})
  (state/set-edit-content! (state/get-edit-input-id) ""))

(defn kill-line-before! []
  (save-current-block! {:force? true})
  (util/kill-line-before! (state/get-input)))

(defn kill-line-after! []
  (save-current-block! {:force? true})
  (util/kill-line-after! (state/get-input)))

(defn beginning-of-block []
  (cursor/move-cursor-to (state/get-input) 0))

(defn end-of-block []
  (cursor/move-cursor-to-end (state/get-input)))

(defn cursor-forward-word []
  (cursor/move-cursor-forward-by-word (state/get-input)))

(defn cursor-backward-word []
  (cursor/move-cursor-backward-by-word (state/get-input)))

(defn backward-kill-word []
  (let [input (state/get-input)]
    (save-current-block! {:force? true})
    (util/backward-kill-word input)
    (state/set-edit-content! (state/get-edit-input-id) (.-value input))))

(defn forward-kill-word []
  (let [input (state/get-input)]
    (save-current-block! {:force? true})
    (util/forward-kill-word input)
    (state/set-edit-content! (state/get-edit-input-id) (.-value input))))

(defn collapsable? [block-id]
  (when block-id
    (if-let [block (db-model/query-block-by-uuid block-id)]
      (and
       (not (util/collapsed? block))
       (db-model/has-children? block-id))
      false)))

(defn all-blocks-with-level
  "Return all blocks associated with correct level
   if :root-block is not nil, only return root block with its children
   if :expanded? true, return expanded children
   if :collapse? true, return without any collapsed children
   if :incremental? true, collapse/expand will be step by step
   for example:
   - a
    - b (collapsed)
     - c
     - d
    - e
   return:
    blocks
    [{:block a :level 1}
     {:block b :level 2}
     {:block e :level 2}]"
  [{:keys [collapse? expanded? incremental? root-block]
    :or {collapse? false expanded? false incremental? true root-block nil}}]
  (when-let [page (or (state/get-current-page)
                      (date/today))]
    (let [block? (util/uuid-string? page)
          block-id (or root-block (and block? (uuid page)))
          blocks (if block-id
                   (db/get-block-and-children (state/get-current-repo) block-id)
                   (db/get-page-blocks-no-cache page))
          root-block (or block-id root-block)]
      (if incremental?
        (let [blocks (tree/blocks->vec-tree blocks (or block-id page))]
          (->>
           (cond->> blocks
             root-block
             (map (fn find [root]
                    (if (= root-block (:block/uuid root))
                      root
                      (first (filter find (:block/children root []))))))

             collapse?
             (w/postwalk
              (fn [b]
                (if (and (map? b)
                         (util/collapsed? b)
                         (not= root-block (:block/uuid b)))
                  (assoc b :block/children []) b)))

             true
             (mapcat (fn [x] (tree-seq map? :block/children x)))

             expanded?
             (filter (fn [b] (collapsable? (:block/uuid b))))

             true
             (map (fn [x] (dissoc x :block/children))))
           (remove nil?)))

        (cond->> blocks
          collapse?
          (filter util/collapsed?)

          expanded?
          (filter (fn [b] (collapsable? (:block/uuid b))))

          true
          (remove nil?))))))

(defn- skip-collapsing-in-db?
  []
  (let [config (:config (state/get-editor-args))]
    (:ref? config)))

(defn- set-blocks-collapsed!
  [block-ids value]
  (let [block-ids (map (fn [block-id] (if (string? block-id) (uuid block-id) block-id)) block-ids)
        repo (state/get-current-repo)
        value (boolean value)]
    (when repo
      (ds/auto-transact!
       [txs-state (ds/new-outliner-txs-state)]
       {:outliner-op :collapse-expand-blocks
        :skip-transact? false}
       (doseq [block-id block-ids]
         (when-let [block (db/entity [:block/uuid block-id])]
           (let [current-value (:block/collapsed? block)]
             (when-not (= current-value value)
               (let [block (outliner-core/block {:block/uuid block-id
                                                 :block/collapsed? value})]
                 (outliner-core/save-node block {:txs-state txs-state})))))))
      (let [block-id (first block-ids)
            input-pos (or (state/get-edit-pos) :max)]
        ;; update editing input content
        (when-let [editing-block (state/get-edit-block)]
          (when (= (:block/uuid editing-block) block-id)
            (edit-block! editing-block
                         input-pos
                         (state/get-edit-input-id))))))))

(defn collapse-block! [block-id]
  (when (collapsable? block-id)
    (when-not (skip-collapsing-in-db?)
      (set-blocks-collapsed! [block-id] true)))
  (state/set-collapsed-block! block-id true))

(defn expand-block! [block-id]
  (when-not (skip-collapsing-in-db?)
    (set-blocks-collapsed! [block-id] false)
    (state/set-collapsed-block! block-id false)))

(defn expand!
  ([e] (expand! e false))
  ([e clear-selection?]
   (util/stop e)
   (cond
     (state/editing?)
     (when-let [block-id (:block/uuid (state/get-edit-block))]
       (expand-block! block-id))

     (state/selection?)
     (do
       (->> (get-selected-blocks-with-children)
            (map (fn [dom]
                   (-> (dom/attr dom "blockid")
                       medley/uuid
                       expand-block!)))
            doall)
       (and clear-selection? (clear-selection!)))
     :else
     ;; expand one level
     (let [blocks-with-level (all-blocks-with-level {})
           max-level (or (apply max (map :block/level blocks-with-level)) 99)]
       (loop [level 1]
         (if (> level max-level)
           nil
           (let [blocks-to-expand (->> blocks-with-level
                                       (filter (fn [b] (= (:block/level b) level)))
                                       (filter util/collapsed?))]
             (if (empty? blocks-to-expand)
               (recur (inc level))
               (doseq [{:block/keys [uuid]} blocks-to-expand]
                 (expand-block! uuid))))))))))

(defn collapse!
  ([e] (collapse! e false))
  ([e clear-selection?]
   (when e (util/stop e))
   (cond
     (state/editing?)
     (when-let [block-id (:block/uuid (state/get-edit-block))]
       (collapse-block! block-id))

     (state/selection?)
     (do
       (->> (get-selected-blocks-with-children)
            (map (fn [dom]
                   (-> (dom/attr dom "blockid")
                       medley/uuid
                       collapse-block!)))
            doall)
       (and clear-selection? (clear-selection!)))

     :else
     ;; collapse by one level from outside
     (let [blocks-with-level
           (all-blocks-with-level {:collapse? true})
           max-level (or (apply max (map :block/level blocks-with-level)) 99)]
       (loop [level max-level]
         (if (zero? level)
           nil
           (let [blocks-to-collapse
                 (->> blocks-with-level
                      (filter (fn [b] (= (:block/level b) level)))
                      (filter (fn [b] (collapsable? (:block/uuid b)))))]
             (if (empty? blocks-to-collapse)
               (recur (dec level))
               (doseq [{:block/keys [uuid]} blocks-to-collapse]
                 (collapse-block! uuid))))))))))

(defn collapse-all!
  ([]
   (collapse-all! nil))
  ([block-id]
   (let [blocks (all-blocks-with-level {:incremental? false
                                        :expanded? true
                                        :root-block block-id})
         block-ids (map :block/uuid blocks)]
     (set-blocks-collapsed! block-ids true))))

(defn expand-all!
  ([]
   (expand-all! nil))
  ([block-id]
   (let [blocks (all-blocks-with-level {:incremental? false
                                        :collapse? true
                                        :root-block block-id})
         block-ids (map :block/uuid blocks)]
     (set-blocks-collapsed! block-ids false))))

(defn toggle-open! []
  (let [all-expanded? (empty? (all-blocks-with-level {:incremental? false
                                                      :collapse? true}))]
    (if all-expanded?
      (collapse-all!)
      (expand-all!))))

(defn select-all-blocks!
  []
  (if-let [current-input-id (state/get-edit-input-id)]
    (let [input (gdom/getElement current-input-id)
          blocks-container (util/rec-get-blocks-container input)
          blocks (dom/by-class blocks-container "ls-block")]
      (state/exit-editing-and-set-selected-blocks! blocks))
    (->> (all-blocks-with-level {:collapse? true})
         (map (comp gdom/getElementByClass str :block/uuid))
         state/exit-editing-and-set-selected-blocks!)))

(defn escape-editing
  ([]
   (escape-editing true))
  ([select?]
   (when (state/editing?)
     (if select?
       (->> (:block/uuid (state/get-edit-block))
            select-block!)
       (state/clear-edit!)))))

(defn replace-block-reference-with-content-at-point
  []
  (when-let [{:keys [content start end]} (thingatpt/block-ref-at-point)]
    (let [block-ref-id (subs content 2 (- (count content) 2))]
      (when-let [block (db/pull [:block/uuid (uuid block-ref-id)])]
        (let [block-content (:block/content block)
              format (or (:block/format block) :markdown)
              block-content-without-prop (-> (property/remove-properties format block-content)
                                             (drawer/remove-logbook))]
          (when-let [input (state/get-input)]
            (when-let [current-block-content (gobj/get input "value")]
              (let [block-content* (str (subs current-block-content 0 start)
                                        block-content-without-prop
                                        (subs current-block-content end))]
                (state/set-block-content-and-last-pos! input block-content* 1)))))))))

(defn copy-current-ref
  [block-id]
  (when block-id
    (util/copy-to-clipboard! (util/format "((%s))" (str block-id)))))

(defn delete-current-ref!
  [block ref-id]
  (when (and block ref-id)
    (let [match (re-pattern (str "\\s?" (util/format "\\(\\(%s\\)\\)" (str ref-id))))
          content (string/replace-first (:block/content block) match "")]
      (save-block! (state/get-current-repo)
                   (:block/uuid block)
                   content))))

(defn replace-ref-with-text!
  [block ref-id]
  (when (and block ref-id)
    (let [match (util/format "((%s))" (str ref-id))
          ref-block (db/entity [:block/uuid ref-id])
          block-ref-content (->> (or (:block/content ref-block)
                                     "")
                                 (property/remove-built-in-properties (:block/format ref-block))
                                 (drawer/remove-logbook))
          content (string/replace-first (:block/content block) match
                                        block-ref-content)]
      (save-block! (state/get-current-repo)
                   (:block/uuid block)
                   content))))

(defn replace-ref-with-embed!
  [block ref-id]
  (when (and block ref-id)
    (let [match (util/format "((%s))" (str ref-id))
          content (string/replace-first (:block/content block) match
                                        (util/format "{{embed ((%s))}}"
                                                     (str ref-id)))]
      (save-block! (state/get-current-repo)
                   (:block/uuid block)
                   content))))

(defn block-default-collapsed?
  "Whether a block should be collapsed by default.
  Currently, this handles several cases:
  1. References.
  2. Custom queries."
  [block config]
  (if (or (:ref? config)
          (:custom-query? config))
    (and
     (seq (:block/children block))
     (or
      (:custom-query? config)
      (>= (:ref/level block)
          (state/get-ref-open-blocks-level))))
    (util/collapsed? block)))
