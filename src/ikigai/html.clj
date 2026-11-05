(ns ikigai.html
  "Escaping and URL rules for HTML built from data: `ikigai_fn::html`.

  An HTML page built from strings has three places a value can break out of:
  text, an attribute, and a URL. These are the ecosystem's one set of rules
  for them; a face calls these rather than writing its own escaper.

  Proved by `test/vectors/html_vectors.json` (`escape`, `url_segment`,
  `percent_decode`, `form_pairs`, `safe_href`, `json_attr`), whose `about`
  states the rules. Read alongside ikigai-python `src/ikigai/html.py`."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "test/vectors/html_vectors.json")

(defn html-text
  "Escape `s` for HTML text: each `escape` case's `out`."
  [s]
  (scaffold/not-implemented `html-text proof {:s s}))

(defn attr
  "Escape `s` for a quoted attribute value. The same output as `html-text`,
  under the name that says where it goes: each `escape` case's `out`."
  [s]
  (scaffold/not-implemented `attr proof {:s s}))

(defn url-segment
  "Percent-encode `s` as one URL path segment: each `url_segment` case's
  `out`. Every case also decodes back to `s` in both plus modes."
  [s]
  (scaffold/not-implemented `url-segment proof {:s s}))

(defn percent-decode
  "Decode the percent escapes in `s`, strictly, reading `+` as a space when
  `plus-is-space` is true. Return the decoded text (each `percent_decode`
  case's `out`), or refuse with an `ex-info` whose data names the error
  (`malformed_escape` with the input byte `offset`, or `invalid_utf8`)."
  [s plus-is-space]
  (scaffold/not-implemented `percent-decode proof {:s s :plus-is-space plus-is-space}))

(defn percent-decode-bytes
  "As `percent-decode`, but return the decoded bytes (each case's `bytes`)
  and refuse only a malformed escape."
  [s plus-is-space]
  (scaffold/not-implemented `percent-decode-bytes proof {:s s :plus-is-space plus-is-space}))

(defn form-pairs
  "Parse a complete form body or query into ordered `[name value]` pairs:
  each `form_pairs` case's `out`, or its `error` and `offset` (in the
  original input) as an `ex-info`."
  [s]
  (scaffold/not-implemented `form-pairs proof {:s s}))

(defn safe-href
  "The trimmed link target `s` if it is safe to put in an `href`, or nil when
  it is not a link and the label should be rendered as text: each `safe_href`
  case's `out`. The result is still to be escaped with `attr`."
  [s]
  (scaffold/not-implemented `safe-href proof {:s s}))

(defn json-attr
  "A JSON object, escaped for an attribute, from the `[key value]` pairs in
  order: each `json_attr` case's `out`."
  [pairs]
  (scaffold/not-implemented `json-attr proof {:pairs pairs}))
