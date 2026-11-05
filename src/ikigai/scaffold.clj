(ns ikigai.scaffold
  "Marks the parts of the face that are not written yet.

  Every public function in this repository that has no body yet calls
  `not-implemented`, which throws an `ex-info` carrying
  `:ikigai/not-implemented` (the function's qualified symbol) and
  `:ikigai/proved-by` (the vector file or sibling test that will prove it).
  The pending tests in `test/ikigai/pending.clj` recognize that exception, so
  a function stops counting as pending the moment it stops throwing it.

  Delete this namespace once nothing requires it.")

(defn not-implemented
  "Throw the scaffold's `not implemented yet` exception for `fn-sym`.

  `proved-by` names what will prove the function (a vector file under
  `test/vectors/`, or a sibling face's test). `args` is the map of the
  arguments the call received, kept in the exception data so a caller can see
  what reached the stub."
  [fn-sym proved-by args]
  (throw (ex-info "not implemented yet"
                  {:ikigai/not-implemented fn-sym
                   :ikigai/proved-by proved-by
                   :ikigai/args args})))

(defn not-implemented?
  "True when `e` is the exception `not-implemented` throws."
  [e]
  (boolean (:ikigai/not-implemented (ex-data e))))
