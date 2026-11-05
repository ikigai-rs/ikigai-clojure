# ikigai-clojure local gates: what .github/workflows/ci.yml runs, in the same order.
#
# The devtools gates (`just -f ~/git-personal/ikigai-devtools/justfile gates`) find this
# recipe because the repo has no Cargo.toml, and run it with a full log and a real exit
# status. Each command is joined with `&&`, so the first failure stops the run and its exit
# status is the recipe's.

# Lint, then the tests under babashka, then the same tests on the JVM.
gates:
    bb lint && bb test && clojure -X:test
