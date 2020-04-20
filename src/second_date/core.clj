(ns second-date.core
  "Replacement for `metabase.util.date` that consistently uses `java.time` instead of a mix of `java.util.Date`,
  `java.sql.*`, and Joda-Time."
  (:refer-clojure :exclude [format range])
  (:require [clojure.string :as str]
            [java-time :as t]
            [java-time.core :as t.core]
            [potemkin :as p]
            [second-date.common :as common]
            [second-date.parse :as parse])
  (:import [java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime Period ZonedDateTime]
           [java.time.temporal Temporal TemporalAdjuster WeekFields]
           org.threeten.extra.PeriodDuration))

(comment parse/keep-me)

(p/import-vars
 [parse parse])

(defn- temporal->iso-8601-formatter [t]
  (condp instance? t
    Instant        :iso-offset-date-time
    LocalDate      :iso-local-date
    LocalTime      :iso-local-time
    LocalDateTime  :iso-local-date-time
    OffsetTime     :iso-offset-time
    OffsetDateTime :iso-offset-date-time
    ZonedDateTime  :iso-offset-date-time))

(defn format
  "Format any of the main `java.time` temporal instant classes as a String. Uses ISO-8601 by default, but `formatter`
  can be a `java.time.format.DateTimeFormatter` or keywords naming static formatters as understood by
  [`clojure.java-time`](https://github.com/dm3/clojure.java-time). Check the value of
  `java-time.format/predefined-formatters` for all supported predefined formatters.

    (second-date/format (t/zoned-date-time \"2020-04-01T15:01-07:00[US/Pacific]\"))
    ;; -> \"2020-04-01T16:01:00-07:00\"

    (second-date/format (t/offset-date-time \"2020-04-01T15:01-07:00\"))
    ;; -> \"2020-04-01T16:01:00-07:00\"

    (second-date/format (t/local-date-time \"2020-04-01T15:01\"))
    ;; -> \"2020-04-01T15:01:00\"

    ;; with a different formatter
    (second-date/format :basic-iso-date (t/local-date-time \"2020-04-01T15:01\"))
    ;; ->  \"20200401\"

    ;; it even handles Instants
    (second-date/format :iso-week-date (t/instant \"2020-04-01T15:01:00-07:00\"))
    ;; \"2020-W14-3Z\""
  (^String [t]
   (when t
     (format (temporal->iso-8601-formatter t) t)))

  (^String [formatter t]
   (when t
     (if (t/instant? t)
       (recur formatter (t/zoned-date-time t (t/zone-id "UTC")))
       (t/format formatter t)))))

;; replace the `T` with a space. Easy!
(defn- replace-T-with-space [s]
  (when s
    (str/replace-first s #"(\d{2})T(\d{2})" "$1 $2")))

(defn format-sql
  "Format a temporal value `t` as a SQL-style literal string (for most SQL databases). This is the same as ISO-8601 but
  uses a space rather than of a `T` to separate the date and time components."
  (^String [t]
   (replace-T-with-space (format t)))

  (^String [formatter t]
   (replace-T-with-space (format formatter t))))

(def add-units
  "Units supported by the `add` function."
  #{:millisecond :second :minute :hour :day :week :month :quarter :year})

(defn add
  "Return a temporal value relative to temporal value `t` by adding (or subtracting) a number of units. Returned value
  will be of same class as `t`.

    (add (t/zoned-date-time \"2019-11-05T15:44-08:00[US/Pacific]\") :month 2)
    ->
    (t/zoned-date-time \"2020-01-05T15:44-08:00[US/Pacific]\")"
  (^Temporal [unit amount]
   (add (t/zoned-date-time) unit amount))

  (^Temporal [t unit amount]
   (if (zero? amount)
     t
     (t/plus t (case unit
                 :millisecond (t/millis amount)
                 :second      (t/seconds amount)
                 :minute      (t/minutes amount)
                 :hour        (t/hours amount)
                 :day         (t/days amount)
                 :week        (t/days (* amount 7))
                 :month       (t/months amount)
                 :quarter     (t/months (* amount 3))
                 :year        (t/years amount))))))

;; TIMEZONE FIXME - we should add `:millisecond-of-second` (or `:fraction-of-second`?) and `:second-of-minute` as
;; well. Not sure where we'd use these, but we should have them for consistency
(def extract-units
  "Units which return a (numerical, periodic) component of a date."
  #{:minute-of-hour
    :hour-of-day
    :day-of-week
    :iso-day-of-week
    :day-of-month
    :day-of-year
    :week-of-year
    :iso-week-of-year
    :month-of-year
    :quarter-of-year
    ;; TODO - in this namespace `:year` is something you can both extract and truncate to. In MBQL `:year` is a
    ;; truncation operation. Maybe we should rename this unit to clear up the potential confusion (?)
    :year})

(def ^:private week-fields*
  (common/static-instances WeekFields))

;; this function is separate from the map above mainly to appease Eastwood due to a bug in `clojure/tools.analyzer` —
;; see https://clojure.atlassian.net/browse/TANAL-132
(defn- week-fields ^WeekFields [k]
  (get week-fields* k))

(defn extract
  "Extract a field such as `:minute-of-hour` from a temporal value `t`.

    (extract (t/zoned-date-time \"2019-11-05T15:44-08:00[US/Pacific]\") :day-of-month)
    ;; -> 5

  Values are returned as numbers (currently, always and integers, but this may change if we add support for
  `:fraction-of-second` in the future.)"
  (^Number [unit]
   (extract (t/zoned-date-time) unit))

  (^Number [t unit]
   (t/as t (case unit
             :minute-of-hour   :minute-of-hour
             :hour-of-day      :hour-of-day
             :day-of-week      (.dayOfWeek (week-fields :sunday-start))
             :iso-day-of-week  (.dayOfWeek (week-fields :iso))
             :day-of-month     :day-of-month
             :day-of-year      :day-of-year
             :week-of-year     (.weekOfYear (week-fields :sunday-start))
             :iso-week-of-year (.weekOfYear (week-fields :iso))
             :month-of-year    :month-of-year
             :quarter-of-year  :quarter-of-year
             :year             :year))))

(defmulti ^TemporalAdjuster adjuster
  "Get the custom `TemporalAdjuster` named by `k`.

    ;; adjust 2019-12-10T17:26 to the second week of the year
    (t/adjust #t \"2019-12-10T17:26\" (u.date/adjuster :week-of-year 2)) ;; -> #t \"2019-01-06T17:26\""
  {:arglists '([k & args])}
  (fn [k & _] (keyword k)))

(defmethod adjuster :default
  [k]
  (throw (ex-info (clojure.core/format "No temporal adjuster named %s" k) {})))

(defmethod adjuster :first-day-of-week
  [_]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (t/adjust t :previous-or-same-day-of-week :sunday))))

(defmethod adjuster :first-day-of-iso-week
  [_]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (t/adjust t :previous-or-same-day-of-week :monday))))

(defmethod adjuster :first-day-of-quarter
  [_]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (.with t (.atDay (t/year-quarter t) 1)))))

(defmethod adjuster :first-week-of-year
  [_]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (-> t
          (t/adjust :first-day-of-year)
          (t/adjust (adjuster :first-day-of-week))))))

(defmethod adjuster :week-of-year
  [_ week-of-year]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (-> t
          (t/adjust (adjuster :first-week-of-year))
          (t/plus (t/weeks (dec week-of-year)))))))

;; if you attempt to truncate a `LocalDate` to `:day` or anything smaller we can go ahead and return it as is
(extend-protocol t.core/Truncatable
  LocalDate
  (truncate-to [t unit]
    (case unit
      :millis  t
      :seconds t
      :minutes t
      :hours   t
      :days    t)))

(def truncate-units  "Valid date trucation units"
  #{:millisecond :second :minute :hour :day :week :iso-week :month :quarter :year})

(defn truncate
  "Truncate a temporal value `t` to the beginning of `unit`, e.g. `:hour` or `:day`. Not all truncation units are
  supported on all subclasses of `Temporal` — for example, you can't truncate a `LocalTime` to `:month`, for obvious
  reasons."
  (^Temporal [unit]
   (truncate (t/zoned-date-time) unit))

  (^Temporal [^Temporal t unit]
   (case unit
     :default     t
     :millisecond (t/truncate-to t :millis)
     :second      (t/truncate-to t :seconds)
     :minute      (t/truncate-to t :minutes)
     :hour        (t/truncate-to t :hours)
     :day         (t/truncate-to t :days)
     :week        (-> (.with t (adjuster :first-day-of-week))     (t/truncate-to :days))
     :iso-week    (-> (.with t (adjuster :first-day-of-iso-week)) (t/truncate-to :days))
     :month       (-> (t/adjust t :first-day-of-month)             (t/truncate-to :days))
     :quarter     (-> (.with t (adjuster :first-day-of-quarter))  (t/truncate-to :days))
     :year        (-> (t/adjust t :first-day-of-year)              (t/truncate-to :days)))))

(defn bucket
  "Perform a truncation or extraction unit on temporal value `t`. (These two operations are collectively known as
  'date bucketing' in Metabase code and MBQL, e.g. for date/time columns in MBQL `:breakout` (SQL `GROUP BY`)).

  You can combine this function with `group-by` to do some date/time bucketing in Clojure-land:

    (group-by #(bucket % :quarter-of-year) (map t/local-date [\"2019-01-01\" \"2019-01-02\" \"2019-01-04\"]))
    ;; -> {1 [(t/local-date \"2019-01-01\") (t/local-date \"2019-01-02\")], 2 [(t/local-date \"2019-01-04\")]}"
  ([unit]
   (bucket (t/zoned-date-time) unit))

  ([t unit]
   (cond
     (= unit :default)     t
     (extract-units unit)  (extract t unit)
     (truncate-units unit) (truncate t unit)
     :else                 (throw (ex-info (clojure.core/format "Invalid unit: %s" unit) {:unit unit})))))

(defn range
  "Get a start (by default, inclusive) and end (by default, exclusive) pair of instants for a `unit` span of time
  containing `t`. e.g.

    (range (t/zoned-date-time \"2019-11-01T15:29:00Z[UTC]\") :week)
    ->
    {:start (t/zoned-date-time \"2019-10-27T00:00Z[UTC]\")
     :end   (t/zoned-date-time \"2019-11-03T00:00Z[UTC]\")}"
  ([unit]
   (range (t/zoned-date-time) unit))

  ([t unit]
   (range t unit nil))

  ([t unit {:keys [start end resolution]
            :or   {start      :inclusive
                   end        :exclusive
                   resolution :millisecond}}]
   (let [t (truncate t unit)]
     {:start (case start
               :inclusive t
               :exclusive (add t resolution -1))
      :end   (case end
               :inclusive (add (add t unit 1) resolution -1)
               :exclusive (add t unit 1))})))

(defn comparison-range
  "Generate an range that of instants that when bucketed by `unit` would be `=`, `<`, `<=`, `>`, or `>=` to the value of
  an instant `t` bucketed by `unit`. (`comparison-type` is one of `:=`, `:<`, `:<=`, `:>`, or `:>=`.) By default, the
  start of the resulting range is inclusive, and the end exclusive; this can be tweaked by passing `options`.

    ;; Generate range off instants that have the same MONTH as Nov 18th
    (comparison-range (t/local-date \"2019-11-18\") :month := {:resolution :day})
    ;; -> {:start (t/local-date \"2019-11-01\"), :end (t/local-date \"2019-12-01\")}"
  ([unit comparison-type]
   (comparison-range (t/zoned-date-time) unit comparison-type))

  ([t unit comparison-type]
   (comparison-range t unit comparison-type nil))

  ([t unit comparison-type {:keys [start end resolution]
                            :or   {start      :inclusive
                                   end        :exclusive
                                   resolution :millisecond}
                            :as   options}]
   (case comparison-type
     :<  {:end (case end
                 :inclusive (add (truncate t unit) resolution -1)
                 :exclusive (truncate t unit))}
     :<= {:end (let [t (add (truncate t unit) unit 1)]
                 (case end
                   :inclusive (add t resolution -1)
                   :exclusive t))}
     :>  {:start (let [t (add (truncate t unit) unit 1)]
                   (case start
                     :inclusive t
                     :exclusive (add t resolution -1)))}
     :>= {:start (let [t (truncate t unit)]
                   (case start
                     :inclusive t
                     :exclusive (add t resolution -1)))}
     :=  (range t unit options))))

(defn ^PeriodDuration period-duration
  "Return the Duration between two temporal values `x` and `y`."
  {:arglists '([s] [period] [duration] [period duration] [start end])}
  ([x]
   (when x
     (condp instance? x
       PeriodDuration x
       CharSequence   (PeriodDuration/parse x)
       Period         (PeriodDuration/of ^Period x)
       Duration       (PeriodDuration/of ^Duration x))))

  ([x y]
   (cond
     (and (instance? Period x) (instance? Duration y))
     (PeriodDuration/of x y)

     (instance? Instant x)
     (period-duration (t/offset-date-time x (t/zone-offset 0)) y)

     (instance? Instant y)
     (period-duration x (t/offset-date-time y (t/zone-offset 0)))

     :else
     (PeriodDuration/between x y))))

(defn compare-period-durations
  "With two args: Compare two periods/durations. Returns a negative value if `d1` is shorter than `d2`, zero if they are
  equal, or positive if `d1` is longer than `d2`.

    (u.date/compare-period-durations \"P1Y\" \"P11M\") ; -> 1 (i.e., 1 year is longer than 11 months)

  You can combine this with `period-duration` to compare the duration between two temporal values against another
  duration:

    (u.date/compare-period-durations (u.date/period-duration #t \"2019-01-01\" #t \"2019-07-01\") \"P11M\") ; -> -1

  Note that this calculation is inexact, since it calclates relative to a fixed point in time, but should be
  sufficient for most if not all use cases."
  [d1 d2]
  (when (and d1 d2)
    (let [t (t/offset-date-time "1970-01-01T00:00Z")]
      (compare (.addTo (period-duration d1) t)
               (.addTo (period-duration d2) t)))))

(defn less-than-period-duration?
  "True if period/duration `d1` is shorter than period/duration `d2`."
  [d1 d2]
  (neg? (compare-period-durations d1 d2)))

(defn greater-than-period-duration?
  "True if period/duration `d1` is longer than period/duration `d2`."
  [d1 d2]
  (pos? (compare-period-durations d1 d2)))

(defn- now-of-same-class
  "Return a temporal value representing *now* of the same class as `t`, e.g. for comparison purposes."
  ^Temporal [t]
  (when t
    (condp instance? t
      Instant        (t/instant)
      LocalDate      (t/local-date)
      LocalTime      (t/local-time)
      LocalDateTime  (t/local-date-time)
      OffsetTime     (t/offset-time)
      OffsetDateTime (t/offset-date-time)
      ZonedDateTime  (t/zoned-date-time))))

(defn older-than?
  "True if temporal value `t` happened before some period/duration ago, compared to now. Prefer this over using
  `t/before?` to compare times to now because it is incredibly fussy about the classes of arguments it is passed.

    ;; did `t` happen more than 2 months ago?
    (older-than? t (t/months 2))"
  [t duration]
  (greater-than-period-duration?
   (period-duration t (now-of-same-class t))
   duration))
