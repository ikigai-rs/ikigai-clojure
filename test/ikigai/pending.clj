(ns ikigai.pending
  "Pending vector cases: the face's progress bar.

  A vector case is PENDING while the function that must satisfy it still
  throws the scaffold's `not implemented yet` (see `ikigai.scaffold`). Each
  pending case is reported to `clojure.test` as one `:pending` event naming
  the case and the function, and counted under `:pending`; it is never
  counted as a pass.

  A pending test still asserts something: that each function it names exists
  and is still a stub. So the moment a function is implemented, its pending
  test FAILS, saying to replace those cases with real assertions. A pending
  case can never quietly outlive the stub it waits on."
  (:require [clojure.test :as t]
            [ikigai.scaffold :as scaffold]))

(defonce ^:private reported (atom []))

(defmethod t/report :pending [m]
  (t/inc-report-counter :pending)
  (swap! reported conj m))

(defonce ^:private skipped (atom []))

(defmethod t/report :skipped [m]
  (t/inc-report-counter :skipped)
  (swap! skipped conj m))

(defn reset-reported!
  "Forget the pending and skipped cases reported so far."
  []
  (reset! reported [])
  (reset! skipped []))

(defn skip!
  "Report `n` cases of `what` as skipped on this runtime, saying `why`. A
  skipped case is not a pass: the runner prints every skip with its reason."
  [what n why]
  (t/do-report {:type :skipped :what what :count n :why why}))

(defn skipped-cases
  "The skips reported since the last reset."
  []
  @skipped)

(defn reported-cases
  "The pending cases reported since the last reset."
  []
  @reported)

(defn- arity [v]
  (count (first (:arglists (meta v)))))

(defn still-pending?
  "True when the function `fn-sym` names still throws the scaffold's
  exception. It is called with nil for each argument of its first arity: a
  stub throws before it reads any of them."
  [fn-sym]
  (let [v (requiring-resolve fn-sym)]
    (try
      (apply @v (repeat (arity v) nil))
      false
      (catch Exception e
        (scaffold/not-implemented? e)))))

(defn case-name
  "A name for the `i`th row of `section`: its `name` when it has one,
  otherwise `section[i]`."
  [section i row]
  (or (when (map? row) (get row "name"))
      (str section "[" i "]")))

(defn cases!
  "Report each row of `rows` as a pending case of `section` in `vector-file`,
  waiting on the functions `fn-syms`. Asserts that there are rows and that
  every function in `fn-syms` is still a stub; once one is not, this fails and
  says which cases to turn into assertions."
  [vector-file section fn-syms rows]
  (t/is (seq rows) (str vector-file " has no `" section "` rows"))
  (let [live (vec (remove still-pending? fn-syms))]
    (t/is (empty? live)
          (str (pr-str live) " no longer throws `not implemented yet`: replace the "
               (count rows) " pending `" section "` cases of " vector-file
               " with assertions"))
    (when (empty? live)
      (doseq [[i row] (map-indexed vector rows)]
        (t/do-report {:type :pending
                      :vector vector-file
                      :section section
                      :case (case-name section i row)
                      :fns fn-syms})))))
