(ns ikigai.check
  "The pre-flight check: an action's contract applied to a proposed set of
  named arguments, mirroring `ikigai_core::ActionSpec::check` (by way of
  ikigai-python's `check.py`).

  It answers whether the arguments conform before anything is invoked, as a
  sequence of violations in core's stable order: each input in declaration
  order (an envelope given twice, a name given twice, a missing required
  input, a value outside `one_of`, a value not of its declared XSD class, a
  value's or an input's unmet `needs`), then the groups (exactly, at least, at
  most one of), then the names the contract does not declare. The check is a
  pure function of the two: authority is the capability floor's business
  (`ikigai.capability`), not this.

  A violation is `{:kind k :path p :message m}`: `:kind` is the vectors' word
  (`missing`, `not-one-of`, `implausible`, `unknown`, `exactly-one`,
  `at-least-one`, `at-most-one`, `needs`, `given-twice`, `envelope`), and
  `:path` is `input:{name}`, a group's `group:{kind}:{members}`,
  `input:{name}:value:{value}`, or nil for an undeclared name.

  Proved by `test/vectors/validation_vectors.json` (`cases`)."
  (:require [clojure.string :as str]
            [ikigai.contract :as contract]
            [ikigai.text :as text]))

(def given-twice
  "The detail of every refusal of a name given more than once that is not a
  list argument (core's `GIVEN_TWICE`)."
  "given twice; an argument takes one value, so name it once")

(def ^:private xsd "http://www.w3.org/2001/XMLSchema#")

(def ^:private canonical-separator
  {"comma" "," "whitespace" " " "comma-or-whitespace" "," "newline" "\n"})

(defn- split-white-space [^String value]
  (->> (partition-by text/white-space? value)
       (remove #(text/white-space? (first %)))
       (map #(apply str %))))

(defn split-list
  "`ListSeparator::split`: the items of `value` for the separator word
  `separator` (`comma`, `whitespace`, `comma-or-whitespace`, `newline`), empty
  items dropped.

    (split-list \"comma\" \"minor, info,,\")  ;=> [\"minor\" \"info\"]"
  [separator value]
  (vec (remove #(= "" %)
               (case separator
                 "comma" (map text/trim (str/split value #"," -1))
                 "whitespace" (split-white-space value)
                 "comma-or-whitespace" (split-white-space (str/replace value "," " "))
                 "newline" (map text/trim (str/split value #"\n" -1))
                 (throw (ex-info (str "unknown list separator " (pr-str separator))
                                 {:separator separator}))))))

(defn- given?
  "A group member (or a dependency) is GIVEN only with a value that is not blank."
  [value]
  (and (some? value) (not= "" (text/trim value))))

(defn- quoted [names] (str/join ", " (map #(str "`" % "`") names)))

(defn- plausible? [cls ^String value]
  (condp = cls
    (str xsd "boolean") (contains? #{"true" "false"} value)
    (str xsd "integer") (boolean
                         (when-let [[_ digits] (re-matches #"[+-]?([0-9]+)" value)]
                           (and digits
                                (<= Long/MIN_VALUE (bigint (str/replace value #"^\+" ""))
                                    Long/MAX_VALUE))))
    (str xsd "dateTime") (let [b (.getBytes value "UTF-8")
                               d? (fn [i] (<= 0x30 (aget b i) 0x39))
                               is? (fn [i c] (= (int c) (aget b i)))]
                           (and (>= (alength b) 10)
                                (every? d? [0 1 2 3]) (is? 4 \-) (d? 5) (d? 6) (is? 7 \-)
                                (d? 8) (d? 9)))
    true))

(defn- check-group [group proposed]
  (let [members (vec (get group "members"))
        present (filterv #(given? (get proposed %)) members)
        kind (get group "kind")
        [broken rule] (case kind
                        "exactly-one" [(not= 1 (count present)) "exactly one of"]
                        "at-least-one" [(empty? present) "at least one of"]
                        "at-most-one" [(> (count present) 1) "at most one of"]
                        (throw (ex-info (str "unknown group kind " (pr-str kind)) {:group group})))]
    (when broken
      {:kind kind
       :path (contract/group-segment group)
       :message (str rule " " (quoted members) " is required; "
                     (if (seq present) (str "got " (quoted present)) "got none"))})))

(defn- check-value [arg value]
  (let [nm (get arg "name")
        one-of (vec (get arg "one_of"))
        cls (get arg "class")]
    (cond
      (and (contains? arg "one_of") (not (some #{value} one-of)))
      {:kind "not-one-of" :path (str "input:" nm)
       :message (str "`" value "` is not an accepted value of `" nm "` ("
                     (if (seq one-of)
                       (str "one of: " (str/join ", " one-of))
                       "it accepts none: its enumeration is empty")
                     ")")}

      (and (some? cls) (not (plausible? cls value)))
      {:kind "implausible" :path (str "input:" nm)
       :message (str "`" value "` does not look like a " cls " for `" nm "`")})))

(defn join-repeats
  "One value per name, in first-seen order (core's `joined`): a list
  argument's repeats joined with its separator's canonical character, any
  other name's FIRST value kept. Returns `[joined repeated]`, where `repeated`
  names the names given more than once without being a list.

    (join-repeats {\"inputs\" [{\"name\" \"graph\" \"list\" \"whitespace\"}]}
                  [[\"graph\" \"a\"] [\"graph\" \"b\"]])
    ;=> [{\"graph\" \"a b\"} []]"
  [action pairs]
  (let [order (distinct (map first pairs))
        values (reduce (fn [m [k v]] (update m k (fnil conj []) v)) {} pairs)
        ;; The first declaration of a name decides its list separator.
        lists (reduce (fn [m a] (if (contains? m (get a "name")) m (assoc m (get a "name") (get a "list"))))
                      {} (get action "inputs"))]
    (reduce (fn [[joined repeated] nm]
              (let [given (get values nm)
                    separator (get lists nm)]
                (cond
                  (= 1 (count given)) [(assoc joined nm (first given)) repeated]
                  separator [(assoc joined nm (str/join (canonical-separator separator) given)) repeated]
                  :else [(assoc joined nm (first given)) (conj repeated nm)])))
            [(array-map) []] order)))

(defn check
  "Check the named arguments `args` (a sequence of `[name value]` pairs, in
  the order given, repeats allowed) against `action` (an action map, as the
  vectors carry it). Return the violations in core's order; an empty vector
  means the arguments conform.

    (map :kind (check {\"verb\" \"Source\" \"inputs\" [{\"name\" \"in\" \"required\" true}]}
                      [[\"out\" \"x\"]]))
    ;=> (\"missing\" \"unknown\")"
  [action args]
  (let [pairs (vec args)
        inputs (vec (get action "inputs"))
        [effective repeated] (join-repeats action pairs)
        repeated (set repeated)
        arguments (or (get action "arguments") {})
        carrier (get arguments "envelope")
        fields-given (and (some? carrier)
                          (some #(and (not= carrier (get % "name")) (given? (get effective (get % "name"))))
                                inputs))
        envelope (when (and carrier fields-given (given? (get effective carrier)))
                   [{:kind "envelope" :path (str "input:" carrier)
                     :message (str "the body is given twice: as `" carrier "` and as the fields"
                                   " that belong inside it; give one")}])
        per-input
        (fn [arg]
          (let [nm (get arg "name")
                binding (= "binding" (get arg "source" "argument"))
                skipped (and carrier (if fields-given
                                       (= nm carrier)
                                       (and (not= nm carrier) (not binding))))
                value (get effective nm)
                twice (when (repeated nm)
                        [{:kind "given-twice" :path (str "input:" nm)
                          :message (str "`" nm "` is " given-twice)}])]
            (cond
              skipped []
              (and (get arg "required" true) (not binding) (nil? value))
              (conj (vec twice) {:kind "missing" :path (str "input:" nm)
                                 :message (str "required input `" nm "` is missing")})
              (nil? value) (vec twice)
              :else
              (let [separator (get arg "list")
                    items (if separator (split-list separator value) [value])
                    unmet (fn [needs] (remove #(given? (get effective %)) needs))
                    value-needs (for [item (distinct items)
                                      :let [note (get (get arg "values") item)
                                            missing (when note (unmet (get note "needs")))]
                                      :when (seq missing)]
                                  {:kind "needs" :path (str "input:" nm ":value:" item)
                                   :message (str "`" nm "=" item "` needs " (quoted missing))})
                    own-missing (unmet (get arg "needs"))]
                (vec (concat twice
                             (keep #(check-value arg %) items)
                             value-needs
                             (when (and (given? value) (seq own-missing))
                               [{:kind "needs" :path (str "input:" nm)
                                 :message (str "`" nm "` needs " (quoted own-missing))}])))))))
        groups (keep #(check-group % effective) (get action "groups"))
        unknown (when-not (contains? arguments "open")
                  (let [declared (set (map #(get % "name") inputs))]
                    (for [nm (distinct (map first pairs))
                          :when (not (declared nm))]
                      {:kind "unknown" :path nil
                       :message (str "unknown argument `" nm "` — not in this action's contract")})))]
    (vec (concat envelope (mapcat per-input inputs) groups unknown))))
