(ns ikigai.test-runner
  "One test runner for both runtimes: `bb test` and `clojure -X:test` call
  `run`, so they run the same namespaces and print the same summary.

  It is plain `clojure.test` plus the pending report (`ikigai.pending`): the
  usual counts, then the pending cases per vector file, which is the face's
  progress bar. A run with any failure or error exits 1, and so does a run
  that found no tests at all."
  (:require [clojure.test :as t]
            [ikigai.pending :as pending]))

(def namespaces
  "The test namespaces, in the order they run."
  '[ikigai.scaffold-test ikigai.vectors-test
    ikigai.postcard-test ikigai.wire-test ikigai.helpers-test ikigai.template-test
    ikigai.compose-test ikigai.contract-test ikigai.client-test ikigai.interop-test ikigai.serve-test
    ikigai.serve-interop-test ikigai.explore-test])

(defn- print-pending [verbose]
  (let [cases (pending/reported-cases)]
    (println)
    (println (str "Pending: " (count cases) " vector cases wait on a stub."))
    (doseq [[[vector-file section] group] (sort-by key (group-by (juxt :vector :section) cases))]
      (println (format "  %-28s %-16s %4d  %s"
                       vector-file section (count group)
                       (pr-str (:fns (first group)))))
      (when verbose
        (doseq [c group]
          (println (str "      " (:case c)))))))
  (when-let [skips (seq (pending/skipped-cases))]
    (println)
    (println (str "Skipped on this runtime: " (reduce + (map :count skips)) " cases."))
    (doseq [{:keys [what why] n :count} skips]
      (println (format "  %-44s %4d  %s" what n why)))))

(defn run-tests
  "Run the test namespaces and return clojure.test's summary, which carries
  `:pending` beside `:pass`, `:fail` and `:error`. With `:verbose true`, list
  every pending case by name."
  [{:keys [verbose] :as opts}]
  (apply require namespaces)
  (reset! @(requiring-resolve 'ikigai.interop/options) (select-keys opts [:require-interop]))
  (pending/reset-reported!)
  (let [summary (apply t/run-tests namespaces)]
    (print-pending verbose)
    summary))

(defn run
  "Run the tests and exit: 0 when nothing failed or errored, 1 otherwise."
  [opts]
  (let [{:keys [test fail error]} (run-tests opts)]
    (shutdown-agents)
    (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1))))
