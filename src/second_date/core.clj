(ns second-date.core
  "Replacement for `metabase.util.date` that consistently uses `java.time` instead of a mix of `java.util.Date`,
  `java.sql.*`, and Joda-Time."
  (:refer-clojure :exclude [format range])
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [java-time.api :as t]
   [java-time.core :as t.core]
   [second-date.common :as common]
   [second-date.parse :as parse])
  (:import
   (java.time DayOfWeek Duration Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime Period ZonedDateTime)
   (java.time.format DateTimeFormatter DateTimeFormatterBuilder FormatStyle TextStyle)
   (java.time.temporal Temporal TemporalAdjuster WeekFields)
   (java.util Calendar Locale)
   (org.threeten.extra PeriodDuration)))

(set! *warn-on-reflection* true)

(defn- add-zone-to-local
  "Converts a temporal type without timezone info to one with zone info (i.e., a `ZonedDateTime`)."
  [t timezone-id]
  (condp instance? t
    LocalDateTime (t/zoned-date-time t (t/zone-id timezone-id))
    LocalDate     (t/zoned-date-time t (t/local-time 0) (t/zone-id timezone-id))
    ;; don't attempt to convert local times to offset times because we have no idea what the offset
    ;; actually should be, since we don't know the date. Since it's not an exact instant in time we're
    ;; not using it to make ranges in MBQL filter clauses anyway
    ;;
    ;; TIMEZONE FIXME - not sure we even want to be adding zone-id info for the timestamps above either
    #_LocalTime   #_ (t/offset-time t (t/zone-id timezone-id))
    t))

(defn parse
  "With one arg, parse a temporal literal into a corresponding `java.time` class, such as `LocalDate` or
  `OffsetDateTime`. With a second arg, literals that do not explicitly specify a timezone are interpreted as being in
  `timezone-id`."
  ([s]
   (parse/parse s))

  ([s default-timezone-id]
   (let [result (parse s)]
     (if-not default-timezone-id
       result
       (let [result-with-timezone (add-zone-to-local result default-timezone-id)]
         (when-not (= result result-with-timezone)
           (log/tracef "Applying default timezone %s to temporal literal without timezone '%s' -> %s"
                       default-timezone-id s (pr-str result-with-timezone)))
         result-with-timezone)))))

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
  "Format temporal value `t`, by default as an ISO-8601 date/time/datetime string. By default `t` is formatted in a way
  that's appropriate for its type, e.g. a `LocalDate` is formatted as year-month-day. You can optionally pass
  `formatter` to format a different way. `formatter` can be:

   1. A keyword name of a predefined formatter. Eval

       (keys java-time.format/predefined-formatters)

     for a list of predefined formatters.

  2. An instance of `java.time.format.DateTimeFormatter`. You can use utils in `second-date.parse.builder` to
     help create one of these formatters.

  3. A format String e.g. `YYYY-MM-dd`"
  (^String [t]
   (when t
     (format (temporal->iso-8601-formatter t) t)))

  (^String [formatter t]
   (format formatter t nil))

  (^String [formatter t locale]
   (cond
     (t/instant? t)
     (recur formatter (t/zoned-date-time t (t/zone-id "UTC")) locale)

     locale
     (recur (.withLocale (t/formatter formatter) (common/locale locale)) t nil)

     :else
     (t/format formatter t))))

(defn format-rfc3339
  "Format temporal value `t`, as an RFC3339 datetime string."
  [t]
  (cond
    (instance? Instant t)
    (recur (t/zoned-date-time t (t/zone-id "UTC")))

    ;; the rfc3339 format requires a timezone component so convert any local datetime/date to zoned
    (instance? LocalDateTime t)
    (recur (t/zoned-date-time t (t/zone-id)))

    (instance? LocalDate t)
    (recur (t/zoned-date-time t (t/local-time 0) (t/zone-id)))

    :else
    (t/format "yyyy-MM-dd'T'hh:mm:ss.SSXXX" t)))

(defn format-sql
  "Format a temporal value `t` as a SQL-style literal string (for most SQL databases). This is the same as ISO-8601 but
  uses a space rather than of a `T` to separate the date and time components."
  ^String [t]
  ;; replace the `T` with a space. Easy!
  (str/replace-first (format t) #"(\d{2})T(\d{2})" "$1 $2"))

(def ^:private ^{:arglists '(^java.time.format.DateTimeFormatter [klass])} class->human-readable-formatter
  {LocalDate      (DateTimeFormatter/ofLocalizedDate FormatStyle/LONG)
   LocalTime      (DateTimeFormatter/ofLocalizedTime FormatStyle/MEDIUM)
   LocalDateTime  (let [builder (doto (DateTimeFormatterBuilder.)
                                  (.appendLocalized FormatStyle/LONG FormatStyle/MEDIUM))]
                    (.toFormatter builder))
   OffsetTime     (let [builder (doto (DateTimeFormatterBuilder.)
                                  (.append (DateTimeFormatter/ofLocalizedTime FormatStyle/MEDIUM))
                                  (.appendLiteral " (")
                                  (.appendLocalizedOffset TextStyle/FULL)
                                  (.appendLiteral ")"))]
                    (.toFormatter builder))
   OffsetDateTime (let [builder (doto (DateTimeFormatterBuilder.)
                                  (.appendLocalized FormatStyle/LONG FormatStyle/MEDIUM)
                                  (.appendLiteral " (")
                                  (.appendLocalizedOffset TextStyle/FULL)
                                  (.appendLiteral ")"))]
                    (.toFormatter builder))
   ZonedDateTime  (let [builder (doto (DateTimeFormatterBuilder.)
                                  (.appendLocalized FormatStyle/LONG FormatStyle/MEDIUM)
                                  (.appendLiteral " (")
                                  (.appendZoneText TextStyle/FULL)
                                  (.appendLiteral ")"))]
                    (.toFormatter builder))})

(defn format-human-readable
  "Format a temporal value `t` in a human-friendly way for `locale`.

    (format-human-readable #t \"2021-04-02T14:42:09.524392-07:00[US/Pacific]\" \"es-MX\")
    ;; -> \"2 de abril de 2021 02:42:09 PM PDT\""
  [t locale]
  (when t
    (if-let [formatter (some (fn [[klass formatter]]
                               (when (instance? klass t)
                                 formatter))
                             class->human-readable-formatter)]
      (format formatter t locale)
      (throw (ex-info (format "Don't know how to format a %s as a human-readable date/time"
                              (some-> t class .getCanonicalName))
                      {:t t})))))

(def add-units
  "A list of units that can be added to a temporal value."
  #{:millisecond :second :minute :hour :day :week :month :quarter :year})

(defn add
  "Return a temporal value relative to temporal value `t` by adding (or subtracting) a number of units. Returned value
  will be of same class as `t`.

    (add (t/zoned-date-time \"2019-11-05T15:44-08:00[US/Pacific]\") :month 2)
    ->
    (t/zoned-date-time \"2020-01-05T15:44-08:00[US/Pacific]\")"
  ([unit amount]
   (add (t/zoned-date-time) unit amount))

  ([t unit amount]
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

;; TIMEZONE FIXME - we should add `:millisecond-of-second` (or `:fraction-of-second`?) .
;; Not sure where we'd use these, but we should have them for consistency
(def extract-units
  "Units which return a (numerical, periodic) component of a date"
  #{:second-of-minute
    :minute-of-hour
    :hour-of-day
    :day-of-week
    :day-of-month
    :day-of-year
    :week-of-year
    :month-of-year
    :quarter-of-year
    ;; TODO - in this namespace `:year` is something you can both extract and truncate to. In MBQL `:year` is a truncation
    ;; operation. Maybe we should rename this unit to clear up the potential confusion (?)
    :year})

(def ^:private ^{:arglists '(^java.time.DayOfWeek [k])} keyword->day-of-week
  (let [m (common/static-instances DayOfWeek)]
    (fn [k]
      (or (get m k)
          (throw (ex-info (format "Invalid day of week: %s" (pr-str k))
                          {:k k, :allowed (keys m)}))))))

(defprotocol CoerceToDayOfWeek
  (day-of-week ^java.time.DayOfWeek [this]
    "Coerce `this` to a `DayOfWeek`."))

(extend-protocol CoerceToDayOfWeek
  nil
  (day-of-week [_this]
    nil)

  Integer
  (day-of-week [^Integer n]
    (java.time.DayOfWeek/of n))

  Long
  (day-of-week [^Long n]
    (java.time.DayOfWeek/of n))

  clojure.lang.Keyword
  (day-of-week [k]
    (keyword->day-of-week k))

  String
  (day-of-week [s]
    (keyword->day-of-week (keyword (common/lower-case-en s))))

  DayOfWeek
  (day-of-week [this]
    this))

(defn current-locale-first-day-of-week
  "Get the first day of the week for the current locale."
  ^DayOfWeek []
  (day-of-week (.getFirstDayOfWeek (Calendar/getInstance (Locale/getDefault)))))

(def ^:dynamic ^DayOfWeek *default-first-day-of-week*
  "If bound, this is used as the [[default-first-day-of-week]] if it was not explicitly specified."
  nil)

(defn- default-first-day-of-week
  ^DayOfWeek []
  (or (some-> *default-first-day-of-week* day-of-week)
      (current-locale-first-day-of-week)))

(defn- week-fields
  "Create a new instance of a `WeekFields`, which is used for localized day-of-week, week-of-month, and week-of-year.

    (week-fields :monday) ; -> #object[java.time.temporal.WeekFields \"WeekFields[MONDAY,1]\"]"
  (^WeekFields [first-day-of-week]
   ;; TODO -- ISO weeks only consider a week to be in a year if it has 4+ days in that year... `:week-of-year`
   ;; extraction is liable to be off for people who expect that definition of "week of year". We should probably make
   ;; this a Setting. See #15039 for more information
   (week-fields first-day-of-week 1))

  (^WeekFields [first-day-of-week ^Integer minimum-number-of-days-in-first-week]
   (let [first-day-of-week (if first-day-of-week
                             (day-of-week first-day-of-week)
                             (default-first-day-of-week))]
     (WeekFields/of first-day-of-week minimum-number-of-days-in-first-week))))

(defn extract
  "Extract a field such as `:minute-of-hour` from a temporal value `t`.

    (extract (t/zoned-date-time \"2019-11-05T15:44-08:00[US/Pacific]\") :day-of-month)
    ;; -> 5

  Values are returned as numbers (currently, always and integers, but this may change if we add support for
  `:fraction-of-second` in the future.)"
  ([unit]
   (extract (t/zoned-date-time) unit))

  ([t unit]
   (extract t unit nil))

  ([t unit {:keys [first-day-of-week], :as _options}]
   (t/as t (case unit
             :second-of-minute :second-of-minute
             :minute-of-hour   :minute-of-hour
             :hour-of-day      :hour-of-day
             :day-of-week      (.dayOfWeek (week-fields first-day-of-week))
             :day-of-month     :day-of-month
             :day-of-year      :day-of-year
             :week-of-year     (.weekOfYear (week-fields first-day-of-week))
             :month-of-year    :month-of-year
             :quarter-of-year  :quarter-of-year
             :year             :year))))

(defmulti ^TemporalAdjuster -adjuster
  "Implementation for [[adjuster]]."
  {:arglists '([adjuster-type options])}
  (fn [adjuster-type _options]
    adjuster-type))

(defmethod -adjuster :default
  [adjuster-type _options]
  (throw (ex-info (format "No temporal adjuster named %s" adjuster-type)
                  {:k adjuster-type})))

(defmethod -adjuster :first-day-of-week
  [_adjuster-type {:keys [first-day-of-week], :as _options}]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (let [first-day-of-week (if first-day-of-week
                                (day-of-week first-day-of-week)
                                (default-first-day-of-week))]
        (t/adjust t :previous-or-same-day-of-week first-day-of-week)))))

(defmethod -adjuster :first-day-of-quarter
  [_adjuster-type _options]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (.with t (.atDay (t/year-quarter t) 1)))))

(defmethod -adjuster :first-week-of-year
  [_adjuster-type options]
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (-> t
          (t/adjust :first-day-of-year)
          (t/adjust (-adjuster :first-day-of-week options))))))

(defmethod -adjuster :week-of-year
  [_adjuster-type {:keys [week-of-year], :as options}]
  {:pre [(integer? week-of-year)]}
  (reify TemporalAdjuster
    (adjustInto [_ t]
      (-> t
          (t/adjust (-adjuster :first-week-of-year options))
          (t/plus (t/weeks (dec week-of-year)))))))

(defn adjuster
  "Get the custom `TemporalAdjuster` named by `k`.

    ;; adjust 2019-12-10T17:26 to the second week of the year
    (t/adjust #t \"2019-12-10T17:26\" (date/adjuster :week-of-year 2)) ;; -> #t \"2019-01-06T17:26\""
  ([adjuster-type]
   (-adjuster adjuster-type nil))
  ([adjuster-type options]
   (-adjuster adjuster-type options)))

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

;;; See https://github.com/dm3/clojure.java-time/issues/95. We need to d2 the `java-time/truncate-to` copy of the
;;; actual underlying method since `extend-protocol` mutates the var
(alter-var-root #'t/truncate-to (constantly t.core/truncate-to))

(def truncate-units
  "Valid date trucation units"
  #{:millisecond :second :minute :hour :day :week :month :quarter :year})

(defn truncate
  "Truncate a temporal value `t` to the beginning of `unit`, e.g. `:hour` or `:day`. Not all truncation units are
  supported on all subclasses of `Temporal` — for example, you can't truncate a `LocalTime` to `:month`, for obvious
  reasons.

  `options` are passed to [[adjuster]]/[[-adjuster]] and can include things like `:first-day-of-week` or
  `:first-week-of-year`."
  ([unit]
   (truncate (t/zoned-date-time) unit))

  ([t unit]
   (truncate t unit nil))

  ([^Temporal t unit {:keys [_first-day-of-week], :as options}]
   (case unit
     :default     t
     :millisecond (t/truncate-to t :millis)
     :second      (t/truncate-to t :seconds)
     :minute      (t/truncate-to t :minutes)
     :hour        (t/truncate-to t :hours)
     :day         (t/truncate-to t :days)
     :week        (-> (.with t (adjuster :first-day-of-week options))
                      (t/truncate-to :days))
     :month       (-> (t/adjust t :first-day-of-month)
                      (t/truncate-to :days))
     :quarter     (-> (.with t (adjuster :first-day-of-quarter options))
                      (t/truncate-to :days))
     :year        (-> (t/adjust t :first-day-of-year)
                      (t/truncate-to :days)))))

(defn bucket
  "Perform a truncation or extraction unit on temporal value `t`. (These two operations are collectively known as
  'date bucketing' in Metabase code and MBQL, e.g. for date/time columns in MBQL `:breakout` (SQL `GROUP BY`)).

  You can combine this function with `group-by` to do some date/time bucketing in Clojure-land:

    (group-by #(bucket % :quarter-of-year) (map t/local-date [\"2019-01-01\" \"2019-01-02\" \"2019-01-04\"]))
    ;; -> {1 [(t/local-date \"2019-01-01\") (t/local-date \"2019-01-02\")], 2 [(t/local-date \"2019-01-04\")]}"
  ([unit]
   (bucket (t/zoned-date-time) unit))

  ([t unit]
   (bucket t unit nil))

  ([t unit options]
   (cond
     (= unit :default)     t
     (extract-units unit)  (extract t unit options)
     (truncate-units unit) (truncate t unit options)
     :else                 (throw (ex-info (format "Invalid unit: %s" (pr-str unit))
                                           {:unit unit})))))

(defn range
  "Get a start (by default, inclusive) and end (by default, exclusive) pair of instants for a `unit` span of time
  containing `t`. e.g.

    (range (t/zoned-date-time \"2019-11-01T15:29:00Z[UTC]\") :week)
    ->
    {:start (t/zoned-date-time \"2019-10-27T00:00Z[UTC]\")
     :end   (t/zoned-date-time \"2019-11-03T00:00Z[UTC]\")}

  Other options like `:first-day-of-week` are passed to [[truncate]] if it is needed."
  ([unit]
   (range (t/zoned-date-time) unit))

  ([t unit]
   (range t unit nil))

  ([t unit
    {:keys [start end resolution _first-day-of-week]
     :or   {start      :inclusive
            end        :exclusive
            resolution :millisecond}
     :as   options}]
   (let [t (truncate t unit options)]
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

  ([t unit comparison-type {:keys [start end resolution _first-day-of-week]
                            :or   {start      :inclusive
                                   end        :exclusive
                                   resolution :millisecond}
                            :as   options}]
   (case comparison-type
     :<  {:end (case end
                 :inclusive (add (truncate t unit options) resolution -1)
                 :exclusive (truncate t unit options))}
     :<= {:end (let [t (add (truncate t unit options) unit 1)]
                 (case end
                   :inclusive (add t resolution -1)
                   :exclusive t))}
     :>  {:start (let [t (add (truncate t unit options) unit 1)]
                   (case start
                     :inclusive t
                     :exclusive (add t resolution -1)))}
     :>= {:start (let [t (truncate t unit options)]
                   (case start
                     :inclusive t
                     :exclusive (add t resolution -1)))}
     :=  (range t unit options))))

;; Moving the type hints to the arg lists makes clj-kondo happy, but breaks eastwood (and maybe causes reflection
;; warnings) at the call sites.
#_{:clj-kondo/ignore [:non-arg-vec-return-type-hint]}
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

    (date/compare-period-durations \"P1Y\" \"P11M\") ; -> 1 (i.e., 1 year is longer than 11 months)

  You can combine this with `period-duration` to compare the duration between two temporal values against another
  duration:

    (date/compare-period-durations (date/period-duration #t \"2019-01-01\" #t \"2019-07-01\") \"P11M\") ; -> -1

  Note that this calculation is inexact, since it calclates relative to a fixed point in time, but should be
  sufficient for most if not all use cases."
  [d1 d2]
  (when (and d1 d2)
    (let [t (t/offset-date-time "1970-01-01T00:00Z")]
      (compare (.addTo (period-duration d1) t)
               (.addTo (period-duration d2) t)))))

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

(defprotocol WithTimeZoneSameInstant
  "Protocol for converting a temporal value to an equivalent one in a given timezone."
  (^{:style/indent 0} with-time-zone-same-instant [t ^java.time.ZoneId zone-id]
    "Convert a temporal value to an equivalent one in a given timezone. For local temporal values, this simply
    converts it to the corresponding offset/zoned type; for offset/zoned types, this applies an appropriate timezone
    shift."))

(extend-protocol WithTimeZoneSameInstant
  ;; convert to a OffsetTime with no offset (UTC); the OffsetTime method impl will apply the zone shift.
  LocalTime
  (with-time-zone-same-instant [t zone-id]
    (t/offset-time t (common/standard-offset zone-id)))

  OffsetTime
  (with-time-zone-same-instant [t ^java.time.ZoneId zone-id]
    (t/with-offset-same-instant t (common/standard-offset zone-id)))

  LocalDate
  (with-time-zone-same-instant [t zone-id]
    (t/offset-date-time t (t/local-time 0) zone-id))

  LocalDate
  (with-time-zone-same-instant [t zone-id]
    (t/offset-date-time t (t/local-time 0) zone-id))

  LocalDateTime
  (with-time-zone-same-instant [t zone-id]
    (t/offset-date-time t zone-id))

  ;; instants are always normalized to UTC, so don't make any changes here. If you want to format in a different zone,
  ;; convert to an OffsetDateTime or ZonedDateTime first.
  Instant
  (with-time-zone-same-instant [t _]
    t)

  OffsetDateTime
  (with-time-zone-same-instant [t ^java.time.ZoneId zone-id]
    ;; calculate the zone offset applicable for the date in question
    (if (or (= t OffsetDateTime/MAX)
            (= t OffsetDateTime/MIN))
      t
      (let [rules  (.getRules zone-id)
            offset (.getOffset rules (t/instant t))]
        (t/with-offset-same-instant t offset))))

  ZonedDateTime
  (with-time-zone-same-instant [t zone-id]
    (t/with-zone-same-instant t zone-id)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      Etc                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn install-print-methods!
  "Add [[print-method]] and [[print-dup]] entries for various temporal classes so they print as string literals like

    #second-date/t \"2024-01-03T13:38:00.000\""
  ([]
   (install-print-methods! 'second-date/t))

  ([data-reader-symbol]
   ;; Mainly for REPL usage. Have various temporal types print as a `java-time` function call you can use
   (doseq [[klass _f-symb] {Instant        't/instant
                            LocalDate      't/local-date
                            LocalDateTime  't/local-date-time
                            LocalTime      't/local-time
                            OffsetDateTime 't/offset-date-time
                            OffsetTime     't/offset-time
                            ZonedDateTime  't/zoned-date-time}]
     (defmethod print-method klass
       [t writer]
       ((get-method print-dup klass) t writer))

     (defmethod print-dup klass
       [t ^java.io.Writer writer]
       (.write writer (clojure.core/format "#%s \"%s\"" data-reader-symbol (str t)))))

   (defmethod print-method PeriodDuration
     [d writer]
     ((get-method print-dup PeriodDuration) d writer))

   (defmethod print-dup PeriodDuration
     [d ^java.io.Writer writer]
     (.write writer (clojure.core/format "(second-date.core/period-duration %s)" (pr-str (str d)))))

   (defmethod print-method Period
     [d writer]
     (print-method (list 't/period (str d)) writer))

   (defmethod print-method Duration
     [d writer]
     (print-method (list 't/duration (str d)) writer))))
