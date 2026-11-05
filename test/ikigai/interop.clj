(ns ikigai.interop
  "Running a real `ikigai` binary for the interop tests, and scratch
  directories short enough for a Unix socket.

  The binary is the first `ikigai` on `PATH`. Without one, an interop test
  reports itself as skipped and says why; with the runner's
  `:require-interop true` (CI's interop job), a missing binary FAILS instead,
  because a skipped interop suite would pass green while covering nothing.

  A kernel started here reads nothing of this machine's configuration: it gets
  a scratch config home and a scratch file root, and is stopped by its process
  handle (never by a pattern)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [ikigai.pending :as pending]))

(defonce ^{:doc "The runner's options: `:require-interop`."} options (atom {}))

(defn binary
  "The path of the first executable `ikigai` on `PATH`, or nil."
  []
  (some (fn [dir]
          (let [f (io/file dir "ikigai")]
            (when (and (.isFile f) (.canExecute f)) (str f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn require-binary!
  "The `ikigai` binary, or nil after reporting `what` (`n` cases) as skipped;
  under `:require-interop` a missing binary is a failure instead."
  [what n]
  (or (binary)
      (do (if (:require-interop @options)
            (t/is false (str ":require-interop, and there is no `ikigai` on PATH (" what ")"))
            (pending/skip! what n "no `ikigai` binary on PATH (the interop tests run against the Rust host)"))
          nil)))

(defn scratch-dir
  "A new empty directory under /tmp with a short name, so a Unix socket inside
  it fits macOS's 104-byte `sun_path` (the session scratchpad does not)."
  ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
            (.toPath (io/file "/tmp")) "ikc"
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn delete-tree
  "Delete `dir` and everything under it."
  [dir]
  (doseq [^java.io.File f (reverse (file-seq (io/file dir)))]
    (.delete f)))

(defn wait-for
  "Poll `ready?` every 50 ms for up to `ms` milliseconds; true once it is."
  [ready? ms]
  (loop [left ms]
    (cond
      (ready?) true
      (neg? left) false
      :else (do (Thread/sleep 50) (recur (- left 50))))))

(defn start-kernel
  "Start `ikigai --config-home H --files F serve <socket>` in a scratch
  directory and wait for its socket: `{:socket s :dir d :files f :process p}`."
  [ikigai & extra-args]
  (let [dir (scratch-dir)
        home (doto (io/file dir "home") .mkdirs)
        files (doto (io/file dir "files") .mkdirs)
        sock (io/file dir "k.sock")
        argv (concat [ikigai "--config-home" (str home) "--files" (str files)] extra-args
                     ["serve" (str sock)])
        process (-> (ProcessBuilder. ^java.util.List (vec argv))
                    (.redirectErrorStream true)
                    (.redirectOutput (io/file dir "serve.log"))
                    .start)]
    (when-not (wait-for #(.exists sock) 15000)
      (.destroy process)
      (throw (ex-info (str "ikigai serve did not come up: " (slurp (io/file dir "serve.log"))) {})))
    {:socket (str sock) :dir dir :files files :process process}))

(defn stop-kernel
  "Stop a kernel `start-kernel` started, by its process handle, and remove its
  scratch directory."
  [{:keys [^Process process dir]}]
  (.destroy process)
  (.waitFor process)
  (delete-tree dir))

(defn run-repl
  "Run `ikigai` with each of `commands` as a `-c` batch line (and `mount` as
  `--mount`, `override` as `--override`) in a scratch config home; return its
  stdout and stderr together, or fail naming the exit status."
  [ikigai {:keys [mount override]} & commands]
  (let [dir (scratch-dir)
        argv (concat [ikigai "--config-home" (str (doto (io/file dir "home") .mkdirs))]
                     (when mount ["--mount" mount])
                     (when override ["--override" override])
                     (mapcat (fn [c] ["-c" c]) commands))
        process (-> (ProcessBuilder. ^java.util.List (vec argv))
                    (.redirectErrorStream true)
                    (.redirectInput (io/file "/dev/null"))
                    .start)
        out (slurp (.getInputStream process))
        status (.waitFor process)]
    (delete-tree dir)
    {:out out :exit status}))
