[![License](https://img.shields.io/badge/license-Eclipse%20Public%20License-blue.svg?style=for-the-badge)](https://raw.githubusercontent.com/metabase/second-date/master/LICENSE)
[![GitHub last commit](https://img.shields.io/github/last-commit/metabase/second-date?style=for-the-badge)](https://github.com/metabase/second-date/commits/)
[![cljdoc badge](https://img.shields.io/badge/dynamic/json?color=informational&label=cljdoc&query=results%5B%3F%28%40%5B%22artifact-id%22%5D%20%3D%3D%20%22toucan2%22%29%5D.version&url=https%3A%2F%2Fcljdoc.org%2Fapi%2Fsearch%3Fq%3Dio.github.metabase%2Fsecond-date&style=for-the-badge)](https://cljdoc.org/d/io.github.metabase/second-date/CURRENT)

[![Clojars Project](https://clojars.org/metabase/second-date/latest-version.svg)](https://clojars.org/metabase/second-date)

![OIG2](https://github.com/metabase/second-date/assets/1455846/db59a1ad-ee86-469b-b96e-2f515f28d06f)

(Thanks Bing Image Creator for this totally accurate protrayal of a Clojure library for working with datetimes that is also a second date)

# Second Date

Second Date provides a handful of utility functions for working with `java.time`. The most important are:

###### `parse`

Can parse almost any temporal literal String to the correct `java.time` class.

```clj
(require '[second-date.core :as second-date])

(second-date/parse "2020-04")
;; -> #object[java.time.LocalDate 0x1998e54f "2020-04-01"]

(second-date/parse "2020-04-01")
;; -> #object[java.time.LocalDate 0x1998e54f "2020-04-01"]

(second-date/parse "2020-04-01T15:01")
;; -> #object[java.time.LocalDateTime 0x121829b7 "2020-04-01T15:01"]

(second-date/parse "2020-04-01T15:01-07:00")
;; -> #object[java.time.OffsetDateTime 0x7dc126b0 "2020-04-01T15:01-07:00"]

(second-date/parse "2020-04-01T15:01-07:00[US/Pacific]")
;; -> #object[java.time.ZonedDateTime 0x351fb7c8 "2020-04-01T15:01-07:00[US/Pacific]"]
```

`parse` handles ISO-8601 strings as well as SQL literals (e.g. `2020-04-01 15:01:00`).

You can also use the built-in literal reader `second-date/t`:

```clj
#second-date/t "2020-04-01T15:01"
;; -> #object[java.time.LocalDateTime 0x121829b7 "2020-04-01T15:01"]
```

For more convenience, you may want to give this a shorter data reader tag, for example `#t`, in your own project.

Optionally, you can also have Second Date print Temporals in a REPL-friendly format by installing print methods with
`second-date/install-print-methods!`:

```clj
#second-date/t "2020-04-01T15:01"
;; -> #object[java.time.LocalDateTime 0x121829b7 "2020-04-01T15:01"]

(second-date/install-print-methods!)
#second-date/t "2020-04-01T15:01"
;; -> #second-date/t "2020-04-01T15:01"

;; use a different data reader tag
(second-date/install-print-methods! 't)

#t "2020-04-01T15:01"
;; -> #t "2020-04-01T15:01"
```

###### `format`

Formats any of the main `java.time` temporal instant classes as a String. Uses ISO-8601 by default, but can use any
`java.time.format.DateTimeFormatter` or keywords naming static formatters as understood by
[`clojure.java-time`](https://github.com/dm3/clojure.java-time).

```clj
(require '[java-time :as t]
         '[second-date.core :as second-date])

(second-date/format (t/zoned-date-time "2020-04-01T15:01-07:00[US/Pacific]"))
;; -> "2020-04-01T16:01:00-07:00"

(second-date/format (t/offset-date-time "2020-04-01T15:01-07:00"))
;; -> "2020-04-01T16:01:00-07:00"

(second-date/format (t/local-date-time "2020-04-01T15:01"))
;; -> "2020-04-01T15:01:00"

;; with a different formatter
(second-date/format :basic-iso-date (t/local-date-time "2020-04-01T15:01"))
;; ->  "20200401"

;; it even handles Instants
(second-date/format :iso-week-date (t/instant "2020-04-01T15:01:00-07:00"))
;; "2020-W14-3Z"
```

Check the value of `java-time.format/predefined-formatters` for all supported predefined formatters.

###### `second-date.parse.builder/formatter`

`second-date.parse.builder/formatter` is a Clojure interface for `java.time.format.DateTimeFormatterBuilder`, used to
create `DateTimeFormatter`s.

```clj
(require '[java-time :as t]
         '[second-date :as second-date]
         '[second-date.parse.builder :as b])

(def my-time-formatter
  (b/formatter
   (b/case-insensitive
    (b/value :hour-of-day 2)
    (b/optional
     ":"
     (b/value :minute-of-hour 2)
     (b/optional
      ":"
      (b/value :second-of-minute))))))

;; -> #object[java.time.format.DateTimeFormatter "ParseCaseSensitive(false)Value(HourOfDay,2)[':'Value(MinuteOfHour,2)[':'Value(SecondOfMinute)]]"]

;; you can now use the formatter to format and parse
(second-date/format my-time-formatter (t/zoned-date-time "2020-04-01T15:23:52.878132-07:00[America/Los_Angeles]"))
;; -> "15:23:52"

(second-date/parse my-time-formatter "15:23:52")
;; -> #object[java.time.LocalTime 0x514c293c "15:23:52"]
```

Some example formatters can be found in `second-date.parse`.
