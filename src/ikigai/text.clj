(ns ikigai.text
  "Text rules the Rust kernel applies, where Java's differ: what `str::trim`
  trims and what `char::is_whitespace` holds, which is Unicode White_Space.
  (Java's `Character/isWhitespace` leaves out U+00A0 and U+202F and holds U+001C
  to U+001F; `clojure.string/trim` trims every char up to U+0020.)")

(def white-space
  "Unicode White_Space, as a set of chars: what Rust's `char::is_whitespace`
  holds. Every one is in the Basic Multilingual Plane."
  (into #{} (map char) (concat (range 0x09 0x0E) [0x20 0x85 0xA0 0x1680]
                               (range 0x2000 0x200B) [0x2028 0x2029 0x202F 0x205F 0x3000])))

(defn white-space?
  "True when the char `c` is Unicode White_Space.

    (map white-space? [\\space (char 0xA0) (char 0x1C)])  ;=> (true true false)"
  [c]
  (contains? white-space c))

(defn trim
  "`s` with Unicode White_Space removed from both ends, as Rust's `str::trim`.

    (trim (str (char 0x3000) \" a b \\n\"))  ;=> \"a b\""
  [^String s]
  (let [n (count s)
        start (or (first (remove #(white-space? (.charAt s (int %))) (range n))) n)
        end (or (first (remove #(white-space? (.charAt s (int (dec %)))) (range n start -1))) start)]
    (subs s start end)))
