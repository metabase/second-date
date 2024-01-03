(ns second-date.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [java-time.api :as t]
   [second-date.common :as common]
   [second-date.core :as date]
   [second-date.test-util :as test-util])
  (:import
   (java.time.temporal ChronoField)))

(set! *warn-on-reflection* true)

(date/install-print-methods!)

(deftest ^:synchronized parse-test
  ;; system timezone should not affect the way strings are parsed
  (doseq [system-timezone-id ["UTC" "US/Pacific"]]
    (test-util/with-system-timezone-id system-timezone-id
      (letfn [(message [expected s default-timezone-id]
                (if default-timezone-id
                  (format "parsing '%s' with default timezone id '%s' should give you %s" s default-timezone-id (pr-str expected))
                  (format "parsing '%s' should give you %s" s (pr-str expected))))
              (is-parsed? [expected s default-timezone-id]
                {:pre [(string? s)]}
                (testing "ISO-8601-style literal"
                  (is (= expected
                         (date/parse s default-timezone-id))
                      (message expected s default-timezone-id)))
                (when (str/includes? s "T")
                  (testing "SQL-style literal"
                    (let [s (str/replace s #"T" " ")]
                      (is (= expected
                             (date/parse s default-timezone-id))
                          (message expected s default-timezone-id))
                      (when-let [[_ before-offset offset] (re-find #"(.*)((?:(?:[+-]\d{2}:\d{2})|Z).*$)" s)]
                        (let [s (format "%s %s" before-offset offset)]
                          (testing "w/ space before offset"
                            (is (= expected
                                   (date/parse s default-timezone-id))
                                (message expected s default-timezone-id)))))))))]
        (testing "literals without timezone"
          (doseq [[s expected]
                  {"2019"                    (t/local-date 2019 1 1)
                   "2019-10"                 (t/local-date 2019 10 1)
                   "2019-10-28"              (t/local-date 2019 10 28)
                   "2019-10-28T13"           (t/local-date-time 2019 10 28 13)
                   "2019-10-28T13:14"        (t/local-date-time 2019 10 28 13 14)
                   "2019-10-28T13:14:15"     (t/local-date-time 2019 10 28 13 14 15)
                   "2019-10-28T13:14:15.555" (t/local-date-time 2019 10 28 13 14 15 (* 555 1000 1000))
                   "13:30"                   (t/local-time 13 30)
                   "13:30:20"                (t/local-time 13 30 20)
                   "13:30:20.555"            (t/local-time 13 30 20 (* 555 1000 1000))}]
            (is-parsed? expected s nil)))
        (testing "literals without timezone, but default timezone provided"
          (doseq [[s expected]
                  {"2019"                    (t/zoned-date-time 2019  1  1  0  0  0               0 (t/zone-id "America/Los_Angeles"))
                   "2019-10"                 (t/zoned-date-time 2019 10  1  0  0  0               0 (t/zone-id "America/Los_Angeles"))
                   "2019-10-28"              (t/zoned-date-time 2019 10 28  0  0  0               0 (t/zone-id "America/Los_Angeles"))
                   "2019-10-28T13"           (t/zoned-date-time 2019 10 28 13  0  0               0 (t/zone-id "America/Los_Angeles"))
                   "2019-10-28T13:14"        (t/zoned-date-time 2019 10 28 13 14  0               0 (t/zone-id "America/Los_Angeles"))
                   "2019-10-28T13:14:15"     (t/zoned-date-time 2019 10 28 13 14 15               0 (t/zone-id "America/Los_Angeles"))
                   "2019-10-28T13:14:15.555" (t/zoned-date-time 2019 10 28 13 14 15 (* 555 1000000) (t/zone-id "America/Los_Angeles"))
                   ;; Times without timezone info should always be parsed as `LocalTime` regardless of whether a default
                   ;; timezone if provided. That's because we can't convert the zone to an offset because the offset is up
                   ;; in the air because of daylight savings.
                   "13:30"                   (t/local-time 13 30  0               0)
                   "13:30:20"                (t/local-time 13 30 20               0)
                   "13:30:20.555"            (t/local-time 13 30 20 (* 555 1000000))}]
            (is-parsed? expected s "America/Los_Angeles")))
        (testing "literals with a timezone offset"
          (doseq [[s expected]
                  {"2019-10-28-07:00"              (t/offset-date-time 2019 10 28  0  0  0               0 (t/zone-offset -7))
                   "2019-10-28T13-07:00"           (t/offset-date-time 2019 10 28 13  0  0               0 (t/zone-offset -7))
                   "2019-10-28T13:14-07:00"        (t/offset-date-time 2019 10 28 13 14  0               0 (t/zone-offset -7))
                   "2019-10-28T13:14:15-07:00"     (t/offset-date-time 2019 10 28 13 14 15               0 (t/zone-offset -7))
                   "2019-10-28T13:14:15.555-07:00" (t/offset-date-time 2019 10 28 13 14 15 (* 555 1000000) (t/zone-offset -7))
                   "13:30-07:00"                   (t/offset-time 13 30  0               0 (t/zone-offset -7))
                   "13:30:20-07:00"                (t/offset-time 13 30 20               0 (t/zone-offset -7))
                   "13:30:20.555-07:00"            (t/offset-time 13 30 20 (* 555 1000000) (t/zone-offset -7))}]
            ;; The 'UTC' default timezone ID should be ignored entirely since all these literals specify their offset
            (is-parsed? expected s "UTC")))
        (testing "literals with a timezone id"
          (doseq [[s expected] {"2019-12-13T16:31:00-08:00[US/Pacific]"              (t/zoned-date-time 2019 12 13 16 31  0               0 (t/zone-id "US/Pacific"))
                                "2019-10-28-07:00[America/Los_Angeles]"              (t/zoned-date-time 2019 10 28  0  0  0               0 (t/zone-id "America/Los_Angeles"))
                                "2019-10-28T13-07:00[America/Los_Angeles]"           (t/zoned-date-time 2019 10 28 13  0  0               0 (t/zone-id "America/Los_Angeles"))
                                "2019-10-28T13:14-07:00[America/Los_Angeles]"        (t/zoned-date-time 2019 10 28 13 14  0               0 (t/zone-id "America/Los_Angeles"))
                                "2019-10-28T13:14:15-07:00[America/Los_Angeles]"     (t/zoned-date-time 2019 10 28 13 14 15               0 (t/zone-id "America/Los_Angeles"))
                                "2019-10-28T13:14:15.555-07:00[America/Los_Angeles]" (t/zoned-date-time 2019 10 28 13 14 15 (* 555 1000000) (t/zone-id "America/Los_Angeles"))
                                "13:30-07:00[America/Los_Angeles]"                   (t/offset-time 13 30  0               0 (t/zone-offset -7))
                                "13:30:20-07:00[America/Los_Angeles]"                (t/offset-time 13 30 20               0 (t/zone-offset -7))
                                "13:30:20.555-07:00[America/Los_Angeles]"            (t/offset-time 13 30 20 (* 555 1000000) (t/zone-offset -7))}]
            ;; The 'UTC' default timezone ID should be ignored entirely since all these literals specify their zone ID
            (is-parsed? expected s "UTC")))
        (testing "literals with UTC offset 'Z'"
          (doseq [[s expected] {"2019Z"                    (t/zoned-date-time 2019  1  1  0  0  0               0 (t/zone-id "UTC"))
                                "2019-10Z"                 (t/zoned-date-time 2019 10  1  0  0  0               0 (t/zone-id "UTC"))
                                "2019-10-28Z"              (t/zoned-date-time 2019 10 28  0  0  0               0 (t/zone-id "UTC"))
                                "2019-10-28T13Z"           (t/zoned-date-time 2019 10 28 13  0  0               0 (t/zone-id "UTC"))
                                "2019-10-28T13:14Z"        (t/zoned-date-time 2019 10 28 13 14  0               0 (t/zone-id "UTC"))
                                "2019-10-28T13:14:15Z"     (t/zoned-date-time 2019 10 28 13 14 15               0 (t/zone-id "UTC"))
                                "2019-10-28T13:14:15.555Z" (t/zoned-date-time 2019 10 28 13 14 15 (* 555 1000000) (t/zone-id "UTC"))
                                "13:30Z"                   (t/offset-time 13 30  0               0 (t/zone-offset 0))
                                "13:30:20Z"                (t/offset-time 13 30 20               0 (t/zone-offset 0))
                                "13:30:20.555Z"            (t/offset-time 13 30 20 (* 555 1000000) (t/zone-offset 0))}]
            ;; default timezone ID should be ignored; because `Z` means UTC we should return ZonedDateTimes instead of
            ;; OffsetDateTime
            (is-parsed? expected s "US/Pacific"))))
      (testing "Weird formats"
        (testing "Should be able to parse SQL-style literals where Zone offset is separated by a space, with no colons between hour and minute"
          (is (= (t/offset-date-time "2014-08-01T10:00-07:00")
                 (date/parse "2014-08-01 10:00:00.000 -0700")))
          (is (= (t/offset-date-time "2014-08-01T10:00-07:00")
                 (date/parse "2014-08-01 10:00:00 -0700")))
          (is (= (t/offset-date-time "2014-08-01T10:00-07:00")
                 (date/parse "2014-08-01 10:00 -0700"))))
        (testing "Should be able to parse SQL-style literals where Zone ID is separated by a space, without brackets"
          (is (= (t/zoned-date-time "2014-08-01T10:00Z[UTC]")
                 (date/parse "2014-08-01 10:00:00.000 UTC")))
          (is (= (t/zoned-date-time "2014-08-02T00:00+08:00[Asia/Hong_Kong]")
                 (date/parse "2014-08-02 00:00:00.000 Asia/Hong_Kong"))))
        (testing "Should be able to parse strings with hour-only offsets e.g. '+00'"
          (is (= (t/offset-time "07:23:18.331Z")
                 (date/parse "07:23:18.331-00")))
          (is (= (t/offset-time "07:23:18.000Z")
                 (date/parse "07:23:18-00")))
          (is (= (t/offset-time "07:23:00.000Z")
                 (date/parse "07:23-00")))
          (is (= (t/offset-time "07:23:18.331-08:00")
                 (date/parse "07:23:18.331-08")))
          (is (= (t/offset-time "07:23:18.000-08:00")
                 (date/parse "07:23:18-08")))
          (is (= (t/offset-time "07:23:00.000-08:00")
                 (date/parse "07:23-08")))))
      (testing "nil"
        (is (= nil
               (date/parse nil))
            "Passing `nil` should return `nil`"))
      (testing "blank strings"
        (is (= nil
               (date/parse "")))
        (is (= nil
               (date/parse "   ")))))))

;; TODO - more tests!
(deftest ^:parallel format-test
  (testing "ZonedDateTime"
    (testing "should get formatted as the same way as an OffsetDateTime"
      (is (= "2019-11-01T18:39:00-07:00"
             (date/format (t/zoned-date-time "2019-11-01T18:39:00-07:00[US/Pacific]")))))
    (testing "make sure it can handle different DST offsets correctly"
      (is (= "2020-02-13T16:31:00-08:00"
             (date/format (t/zoned-date-time "2020-02-13T16:31:00-08:00[US/Pacific]"))))))
  (testing "Instant"
    (is (= "1970-01-01T00:00:00Z"
           (date/format (t/instant "1970-01-01T00:00:00Z")))))
  (testing "nil"
    (is (= nil
           (date/format nil))
        "Passing `nil` should return `nil`")))

(deftest ^:parallel format-human-readable-test
  ;; strings are localized slightly differently on different JVMs. For places where there are multiple possible
  ;; correct results, we'll use a set with all the possibilities below and check membership
  (doseq [[t expected] {#second-date/t "2021-04-02T14:42:09.524392-07:00[US/Pacific]" ; ZonedDateTime
                        {:en-US #{"April 2, 2021 2:42:09 PM (Pacific Daylight Time)"
                                  "April 2, 2021, 2:42:09 PM (Pacific Daylight Time)"}
                         :es-MX #{"2 de abril de 2021 02:42:09 PM (Hora de verano del Pacífico)"
                                  "2 de abril de 2021 14:42:09 (Hora de verano del Pacífico)"
                                  "2 de abril de 2021 14:42:09 (hora de verano del Pacífico)"
                                  "2 de abril de 2021, 14:42:09 (Hora de verano del Pacífico)"}}

                        #second-date/t "2021-04-02T14:42:09.524392-07:00" ; OffsetDateTime
                        {:en-US #{"April 2, 2021 2:42:09 PM (GMT-07:00)"
                                  "April 2, 2021, 2:42:09 PM (GMT-07:00)"}
                         :es-MX #{"2 de abril de 2021 02:42:09 PM (GMT-07:00)"
                                  "2 de abril de 2021 14:42:09 (GMT-07:00)"}}

                        #second-date/t "2021-04-02T14:42:09.524392" ; LocalDateTime
                        {:en-US #{"April 2, 2021 2:42:09 PM"
                                  "April 2, 2021, 2:42:09 PM"}
                         :es-MX #{"2 de abril de 2021 02:42:09 PM"
                                  "2 de abril de 2021 14:42:09"}}

                        #second-date/t "2021-04-02" ; LocalDate
                        {:en-US "April 2, 2021"
                         :es-MX "2 de abril de 2021"}

                        #second-date/t "14:42:09.524392-07:00" ; OffsetTime
                        {:en-US "2:42:09 PM (GMT-07:00)"
                         :es-MX #{"02:42:09 PM (GMT-07:00)"
                                  "14:42:09 (GMT-07:00)"}}

                        #second-date/t "14:42:09.524392" ; LocalTime
                        {:en-US "2:42:09 PM"
                         :es-MX #{"02:42:09 PM"
                                  "14:42:09"}}}
          [locale expected] expected]
    (testing (format "%s %s" (.getCanonicalName (class t)) (pr-str t))
      (let [actual (str/replace (date/format-human-readable t locale) \u202f \space)]
        (if (set? expected)
          (is (contains? expected actual))
          (is (= expected actual)))))))

(deftest ^:parallel format-sql-test
  (testing "LocalDateTime"
    (is (= "2019-11-05 19:27:00"
           (date/format-sql (t/local-date-time "2019-11-05T19:27")))))
  (testing "ZonedDateTime"
    (is (= "2019-11-01 18:39:00-07:00"
           (date/format-sql (t/zoned-date-time "2019-11-01T18:39:00-07:00[US/Pacific]")))
        "should get formatted as the same way as an OffsetDateTime")))

(deftest ^:parallel day-of-week-test
  (are [x] (= java.time.DayOfWeek/MONDAY
              (date/day-of-week x))
    java.time.DayOfWeek/MONDAY
    (int 1)
    (long 1)
    :monday
    "MONDAY"
    "monday"))

(deftest ^:parallel adjuster-test
  (let [now (t/zoned-date-time "2019-12-10T17:17:00-08:00[US/Pacific]")]
    (testing "adjust temporal value to first day of week (Sunday)"
      (is (= (t/zoned-date-time "2019-12-08T17:17-08:00[US/Pacific]")
             (t/adjust now (date/adjuster :first-day-of-week {:first-day-of-week :sunday})))))
    (testing "adjust temporal value to first day of first week of year (previous or same Sunday as first day of year)"
      (is (= (t/zoned-date-time "2018-12-30T17:17-08:00[US/Pacific]")
             (t/adjust now (date/adjuster :first-week-of-year {:first-day-of-week :sunday}))
             (t/adjust now (date/adjuster :week-of-year {:first-day-of-week :sunday, :week-of-year 1})))))
    (testing "adjust temporal value to the 50th week of the year"
      (is (= (t/zoned-date-time "2019-12-08T17:17-08:00[US/Pacific]")
             (t/adjust now (date/adjuster :week-of-year {:first-day-of-week :sunday, :week-of-year 50})))))))

(deftest ^:parallel extract-test
  (testing "date/extract with 2 args"
    ;; everything is at `Sunday October 27th 2019 2:03:40.555 PM` or subset thereof
    (let [temporal-category->sample-values {:dates     [(t/local-date 2019 10 27)]
                                            :times     [(t/local-time  14 3 40 (* 555 1000000))
                                                        (t/offset-time 14 3 40 (* 555 1000000) (t/zone-offset -7))]
                                            :datetimes [(t/offset-date-time 2019 10 27 14 3 40 (* 555 1000000) (t/zone-offset -7))
                                                        (t/zoned-date-time  2019 10 27 14 3 40 (* 555 1000000) (t/zone-id "America/Los_Angeles"))]}]
      (doseq [[categories unit->expected] {#{:times :datetimes} {:minute-of-hour 3
                                                                 :hour-of-day    14}
                                           #{:dates :datetimes} {:day-of-week      1
                                                                 :day-of-month     27
                                                                 :day-of-year      300
                                                                 :week-of-year     44
                                                                 :month-of-year    10
                                                                 :quarter-of-year  4
                                                                 :year             2019}}
              category                    categories
              t                           (get temporal-category->sample-values category)
              [unit expected]             unit->expected]
        (is (= expected
               (date/extract t unit {:first-day-of-week :sunday}))
            (format "Extract %s from %s %s should be %s" unit (class t) t expected)))))
  (testing "date/extract with 1 arg (extract from now)"
    (test-util/with-clock #second-date/t "2019-11-18T22:31Z"
      (binding [date/*default-first-day-of-week* :sunday]
        (is (= 2
               (date/extract :day-of-week)))))))

(deftest ^:parallel extract-start-of-week-test
  (testing "`extract` `:day-of-week` and `:week-of-year` should respect the `start-of-week` Setting (metabase/metabase#14294)"
    (doseq [[first-day-of-week unit->expected] {"sunday"    {:week-of-year 9, :day-of-week 3}
                                                "monday"    {:week-of-year 9, :day-of-week 2}
                                                "tuesday"   {:week-of-year 9, :day-of-week 1}
                                                "wednesday" {:week-of-year 8, :day-of-week 7}
                                                "thursday"  {:week-of-year 8, :day-of-week 6}
                                                "friday"    {:week-of-year 8, :day-of-week 5}
                                                "saturday"  {:week-of-year 9, :day-of-week 4}}
            unit [:week-of-year :day-of-week]]
      (testing (str \newline (pr-str (list `date/extract
                                           (symbol "#_Tuesday") #second-date/t "2021-02-23"
                                           unit
                                           {:first-day-of-week first-day-of-week})))
        (is (= (get unit->expected unit)
               (date/extract #second-date/t "2021-02-23" unit {:first-day-of-week first-day-of-week})))))))

(deftest ^:parallel truncate-test
  (testing "date/truncate with 2 args"
    (let [t->unit->expected
          {(t/local-date 2019 10 27)
           {:second   (t/local-date 2019 10 27)
            :minute   (t/local-date 2019 10 27)
            :hour     (t/local-date 2019 10 27)
            :day      (t/local-date 2019 10 27)
            :week     (t/local-date 2019 10 27)
            :month    (t/local-date 2019 10 1)
            :quarter  (t/local-date 2019 10 1)
            :year     (t/local-date 2019 1 1)}

           (t/local-time 14 3 40 (* 555 1000000))
           {:second (t/local-time 14 3 40)
            :minute (t/local-time 14 3)
            :hour   (t/local-time 14)}

           (t/offset-time 14 3 40 (* 555 1000000) (t/zone-offset -7))
           {:second (t/offset-time 14 3 40 0 (t/zone-offset -7))
            :minute (t/offset-time 14 3  0 0 (t/zone-offset -7))
            :hour   (t/offset-time 14 0  0 0 (t/zone-offset -7))}

           (t/offset-date-time 2019 10 27 14 3 40 (* 555 1000000) (t/zone-offset -7))
           {:second   (t/offset-date-time 2019 10 27 14 3 40 0 (t/zone-offset -7))
            :minute   (t/offset-date-time 2019 10 27 14 3  0 0 (t/zone-offset -7))
            :hour     (t/offset-date-time 2019 10 27 14 0  0 0 (t/zone-offset -7))
            :day      (t/offset-date-time 2019 10 27 0  0  0 0 (t/zone-offset -7))
            :week     (t/offset-date-time 2019 10 27 0  0  0 0 (t/zone-offset -7))
            :month    (t/offset-date-time 2019 10  1 0  0  0 0 (t/zone-offset -7))
            :quarter  (t/offset-date-time 2019 10  1 0  0  0 0 (t/zone-offset -7))
            :year     (t/offset-date-time 2019  1  1 0  0  0 0 (t/zone-offset -7))}

           (t/zoned-date-time  2019 10 27 14 3 40 (* 555 1000000) (t/zone-id "America/Los_Angeles"))
           {:second   (t/zoned-date-time  2019 10 27 14 3 40 0 (t/zone-id "America/Los_Angeles"))
            :minute   (t/zoned-date-time  2019 10 27 14 3  0 0 (t/zone-id "America/Los_Angeles"))
            :hour     (t/zoned-date-time  2019 10 27 14 0  0 0 (t/zone-id "America/Los_Angeles"))
            :day      (t/zoned-date-time  2019 10 27  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :week     (t/zoned-date-time  2019 10 27  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :month    (t/zoned-date-time  2019 10  1  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :quarter  (t/zoned-date-time  2019 10  1  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :year     (t/zoned-date-time  2019  1  1  0 0  0 0 (t/zone-id "America/Los_Angeles"))}}]
      (doseq [[t unit->expected] t->unit->expected
              [unit expected]    unit->expected]
        (is (= expected
               (date/truncate t unit {:first-day-of-week :sunday}))
            (format "Truncate %s %s to %s should be %s" (class t) t unit expected)))))
  (testing "date/truncate with 1 arg (truncate now)"
    (test-util/with-clock (t/mock-clock (t/instant "2019-11-18T22:31:00Z"))
      (is (= (t/zoned-date-time "2019-11-18T00:00Z[UTC]")
             (date/truncate :day))))))

(deftest ^:parallel truncate-start-of-week-test
  (testing "`truncate` to `:week` should respect the `start-of-week` Setting (metabase/metabase#14294)"
    (doseq [[first-day-of-week expected] {"sunday"    #second-date/t "2021-02-21"
                                          "monday"    #second-date/t "2021-02-22"
                                          "tuesday"   #second-date/t "2021-02-23"
                                          "wednesday" #second-date/t "2021-02-17"
                                          "thursday"  #second-date/t "2021-02-18"
                                          "friday"    #second-date/t "2021-02-19"
                                          "saturday"  #second-date/t "2021-02-20"}]
      (is (= expected
             (date/truncate #_Tuesday #second-date/t "2021-02-23" :week {:first-day-of-week first-day-of-week}))))))

(deftest ^:parallel bucket-test
  (are [unit expected] (= expected
                          (date/bucket #second-date/t "2024-01-03" unit))
    :month         #second-date/t "2024-01-01"
    :month-of-year 1))

(deftest ^:parallel add-test
  (testing "with 2 args (datetime relative to now)"
    (test-util/with-clock #second-date/t "2019-11-18T22:31:00Z"
      (is (= (t/zoned-date-time "2019-11-20T22:31Z[UTC]")
             (date/add :day 2)))))
  (testing "with 3 args"
    (let [t (t/zoned-date-time "2019-06-14T00:00:00.000Z[UTC]")]
      (doseq [[unit n expected] [[:second  5 "2019-06-14T00:00:05Z[UTC]"]
                                 [:minute  5 "2019-06-14T00:05:00Z[UTC]"]
                                 [:hour    5 "2019-06-14T05:00:00Z[UTC]"]
                                 [:day     5 "2019-06-19T00:00:00Z[UTC]"]
                                 [:week    5 "2019-07-19T00:00:00Z[UTC]"]
                                 [:month   5 "2019-11-14T00:00:00Z[UTC]"]
                                 [:quarter 5 "2020-09-14T00:00:00Z[UTC]"]
                                 [:year    5 "2024-06-14T00:00:00Z[UTC]"]]]
        (is (= (t/zoned-date-time expected)
               (date/add t unit n))
            (format "%s plus %d %ss should be %s" t n unit expected))))))

(deftest ^:parallel range-test
  (testing "with 1 arg (range relative to now)"
    (is (= {:start (t/zoned-date-time "2019-11-17T00:00Z[UTC]")
            :end   (t/zoned-date-time "2019-11-24T00:00Z[UTC]")}
           (test-util/with-clock (t/mock-clock (t/instant "2019-11-18T22:31:00Z"))
             (binding [date/*default-first-day-of-week* :sunday]
               (date/range :week))))))
  (testing "with 2 args"
    (is (= {:start (t/zoned-date-time "2019-10-27T00:00Z[UTC]")
            :end   (t/zoned-date-time "2019-11-03T00:00Z[UTC]")}
           (date/range (t/zoned-date-time "2019-11-01T15:29:00Z[UTC]") :week {:first-day-of-week :sunday}))))
  (testing "with 3 args (start/end inclusitivity options)"
    (testing "exclusive start"
      (is (= {:start (t/local-date-time "2019-10-31T23:59:59.999"), :end (t/local-date-time "2019-12-01T00:00")}
             (date/range (t/local-date-time "2019-11-18T00:00") :month {:start :exclusive}))))
    (testing "inclusive end"
      (is (= {:start (t/local-date-time "2019-11-01T00:00"), :end (t/local-date-time "2019-11-30T23:59:59.999")}
             (date/range (t/local-date-time "2019-11-18T00:00") :month {:end :inclusive}))))
    (testing ":day resolution + inclusive end"
      (is (= {:start (t/local-date "2019-11-01"), :end (t/local-date "2019-11-30")}
             (date/range (t/local-date "2019-11-18") :month {:end :inclusive, :resolution :day}))))))

(deftest ^:parallel comparison-range-test
  (testing "Comparing MONTH"
    (letfn [(comparison-range [comparison-type options]
              (date/comparison-range (t/local-date "2019-11-18") :month comparison-type (merge {:resolution :day} options)))]
      (testing "Month = November"
        (is (= {:start (t/local-date "2019-11-01"), :end (t/local-date "2019-12-01")}
               (comparison-range := nil)))
        (testing "inclusive end"
          (is (= {:start (t/local-date "2019-11-01"), :end (t/local-date "2019-11-30")}
                 (comparison-range := {:end :inclusive}))))
        (testing "exclusive start"
          (is (= {:start (t/local-date "2019-10-31"), :end (t/local-date "2019-12-01")}
                 (comparison-range := {:start :exclusive})))))
      (testing "month < November"
        (is (= {:end (t/local-date "2019-11-01")}
               (comparison-range :< nil)))
        (testing "inclusive end"
          (is (= {:end (t/local-date "2019-10-31")}
                 (comparison-range :< {:end :inclusive})))))
      (testing "month <= November"
        (is (= {:end (t/local-date "2019-12-01")}
               (comparison-range :<= nil)))
        (testing "inclusive end"
          (is (= {:end (t/local-date "2019-11-30")}
                 (comparison-range :<= {:end :inclusive})))))
      (testing "month > November"
        (is (= {:start (t/local-date "2019-12-01")}
               (comparison-range :> nil)))
        (testing "exclusive start"
          (is (= {:start (t/local-date "2019-11-30")}
                 (comparison-range :> {:start :exclusive})))))
      (testing "month >= November"
        (is (= {:start (t/local-date "2019-11-01")}
               (comparison-range :>= nil)))
        (testing "exclusive start"
          (is (= {:start (t/local-date "2019-10-31")}
                 (comparison-range :>= {:start :exclusive})))))))
  (testing "Comparing DAY"
    (letfn [(comparison-range [comparison-type options]
              (date/comparison-range (t/local-date-time "2019-11-18T12:00") :day comparison-type (merge {:resolution :minute} options)))]
      (testing "Day = November 18th"
        (is (= {:start (t/local-date-time "2019-11-18T00:00"), :end (t/local-date-time "2019-11-19T00:00")}
               (comparison-range := nil)))
        (testing "inclusive end"
          (is (= {:start (t/local-date-time "2019-11-18T00:00"), :end (t/local-date-time "2019-11-18T23:59")}
                 (comparison-range := {:end :inclusive}))))
        (testing "exclusive start"
          (is (= {:start (t/local-date-time "2019-11-17T23:59"), :end (t/local-date-time "2019-11-19T00:00")}
                 (comparison-range := {:start :exclusive})))))
      (testing "Day < November 18th"
        (is (= {:end (t/local-date-time "2019-11-18T00:00")}
               (comparison-range :< nil)))
        (testing "inclusive end"
          (is (= {:end (t/local-date-time "2019-11-17T23:59")}
                 (comparison-range :< {:end :inclusive})))))
      (testing "Day <= November 18th"
        (is (= {:end (t/local-date-time "2019-11-19T00:00")}
               (comparison-range :<= nil)))
        (testing "inclusive end"
          (is (= {:end (t/local-date-time "2019-11-18T23:59")}
                 (comparison-range :<= {:end :inclusive})))))
      (testing "Day > November 18th"
        (is (= {:start (t/local-date-time "2019-11-19T00:00")}
               (comparison-range :> nil)))
        (testing "exclusive start"
          (is (= {:start (t/local-date-time "2019-11-18T23:59")}
                 (comparison-range :> {:start :exclusive})))))
      (testing "Day >= November 18th"
        (is (= {:start (t/local-date-time "2019-11-18T00:00")}
               (comparison-range :>= nil)))
        (testing "exclusive start"
          (is (= {:start (t/local-date-time "2019-11-17T23:59")}
                 (comparison-range :>= {:start :exclusive}))))))))

(deftest ^:parallel comparison-range-start-of-week-test
  (testing "`comparison-range` for week should respect the `start-of-week` Setting (metabase/metabase#14294)"
    (doseq [[first-day-of-week expected] {"sunday"    {:start #second-date/t "2021-02-21", :end #second-date/t "2021-02-27"}
                                          "monday"    {:start #second-date/t "2021-02-22", :end #second-date/t "2021-02-28"}
                                          "tuesday"   {:start #second-date/t "2021-02-23", :end #second-date/t "2021-03-01"}
                                          "wednesday" {:start #second-date/t "2021-02-17", :end #second-date/t "2021-02-23"}
                                          "thursday"  {:start #second-date/t "2021-02-18", :end #second-date/t "2021-02-24"}
                                          "friday"    {:start #second-date/t "2021-02-19", :end #second-date/t "2021-02-25"}
                                          "saturday"  {:start #second-date/t "2021-02-20", :end #second-date/t "2021-02-26"}}]
      (testing (format "first day of week = %s" first-day-of-week)
        (let [t #second-date/t "2021-02-23"]
          (is (= expected
                 (merge
                  (date/comparison-range t :week :>= {:first-day-of-week first-day-of-week
                                                      :resolution        :day})
                  (date/comparison-range t :week :<= {:first-day-of-week first-day-of-week
                                                      :resolution        :day
                                                      :end               :inclusive})))))))))

(deftest ^:parallel period-duration-test
  (testing "Creating a period duration from a string"
    (is (= (org.threeten.extra.PeriodDuration/of (t/duration "PT59S"))
           (date/period-duration "PT59S"))))
  (testing "Creating a period duration out of two temporal types of the same class"
    (is (= (date/period-duration "PT1S")
           (date/period-duration (t/offset-date-time "2019-12-03T02:30:05Z") (t/offset-date-time "2019-12-03T02:30:06Z")))))
  (testing "Creating a period duration out of two different temporal types"
    (is (= (date/period-duration "PT59S")
           (date/period-duration (t/instant "2019-12-03T02:30:27Z") (t/offset-date-time "2019-12-03T02:31:26Z"))))))

(deftest ^:parallel older-than-test
  (let [now (t/instant "2019-12-04T00:45:00Z")]
    (test-util/with-clock (t/mock-clock now (t/zone-id "America/Los_Angeles"))
      (testing (str "now = " now)
        (doseq [t ((juxt t/instant t/local-date t/local-date-time t/offset-date-time identity)
                   (t/zoned-date-time "2019-11-01T00:00-08:00[US/Pacific]"))]
          (testing (format "t = %s" (pr-str t))
            (is (= true
                   (date/older-than? t (t/weeks 2)))
                (format "%s happened before 2019-11-19" (pr-str t)))
            (is (= false
                   (date/older-than? t (t/months 2)))
                (format "%s did not happen before 2019-10-03" (pr-str t)))))))))

(deftest ^:synchronized static-instances-locale-test
  (testing "in the Turkish locale, :minute-of-hour can be found"
    (test-util/with-locale "tr"
      (is (some? (:minute-of-hour (common/static-instances ChronoField)))))))

(deftest ^:parallel with-time-zone-same-instant-test
  ;; `t` = original value
  ;; `expected` = the same value when shifted to `zone`
  (doseq [[t expected zone]
          [[(t/zoned-date-time 2011 4 18 0 0 0 0 (t/zone-id "Asia/Tokyo"))
            (t/zoned-date-time "2011-04-17T15:00:00Z[UTC]")
            "UTC"]

           [(t/zoned-date-time 2011 4 18 0 0 0 0 (t/zone-id "Asia/Tokyo"))
            (t/zoned-date-time "2011-04-18T00:00:00+09:00[Asia/Tokyo]")
            "Asia/Tokyo"]

           [(t/zoned-date-time 2011 4 18 0 0 0 0 (t/zone-id "UTC"))
            (t/zoned-date-time "2011-04-18T09:00:00+09:00[Asia/Tokyo]")
            "Asia/Tokyo"]

           [(t/zoned-date-time 2011 4 18 0 0 0 0 (t/zone-id "UTC"))
            (t/zoned-date-time "2011-04-18T00:00:00Z[UTC]")
            "UTC"]

           [(t/offset-date-time 2011 4 18 0 0 0 0 (t/zone-offset 9))
            (t/offset-date-time "2011-04-17T15:00:00Z")
            "UTC"]

           [(t/offset-date-time 2011 4 18 0 0 0 0 (t/zone-offset 9))
            (t/offset-date-time "2011-04-18T00:00:00+09:00")
            "Asia/Tokyo"]

           [(t/offset-date-time 2011 4 18 0 0 0 0 (t/zone-offset 0))
            (t/offset-date-time "2011-04-18T09:00:00+09:00")
            "Asia/Tokyo"]

           ;; instants should return arg as-is since they're always normalized to UTC
           [(t/instant (t/offset-date-time 2011 4 18 0 0 0 0 (t/zone-offset 0)))
            (t/instant "2011-04-18T00:00:00Z")
            "UTC"]

           [(t/instant (t/offset-date-time 2011 4 18 0 0 0 0 (t/zone-offset 0)))
            (t/instant "2011-04-18T00:00:00Z")
            "Asia/Tokyo"]

           [(t/instant (t/offset-date-time 2011 4 18 0 0 0 0 (t/zone-offset 0)))
            (t/instant "2011-04-18T00:00:00Z")
            "UTC"]

           [(t/local-date-time 2011 4 18 0 0 0 0)
            (t/offset-date-time "2011-04-18T00:00:00+09:00")
            "Asia/Tokyo"]

           [(t/local-date-time 2011 4 18 0 0 0 0)
            (t/offset-date-time "2011-04-18T00:00:00Z")
            "UTC"]

           [(t/local-date 2011 4 18)
            (t/offset-date-time "2011-04-18T00:00:00+09:00")
            "Asia/Tokyo"]

           [(t/local-date 2011 4 18)
            (t/offset-date-time "2011-04-18T00:00:00Z")
            "UTC"]

           [(t/offset-time 19 55 0 0 (t/zone-offset 9))
            (t/offset-time "10:55:00Z")
            "UTC"]

           [(t/offset-time 19 55 0 0 (t/zone-offset 9))
            (t/offset-time "19:55:00+09:00")
            "Asia/Tokyo"]

           [(t/offset-time 19 55 0 0 (t/zone-offset 0))
            (t/offset-time "19:55:00Z")
            "UTC"]

           [(t/offset-time 19 55 0 0 (t/zone-offset 0))
            (t/offset-time "04:55:00+09:00")
            "Asia/Tokyo"]

           [(t/local-time 19 55)
            (t/offset-time "19:55:00Z")
            "UTC"]

           [(t/local-time 19 55)
            (t/offset-time "19:55:00+09:00")
            "Asia/Tokyo"]]]
    ;; results should be completely independent of the system clock
    (doseq [[clock-instant clock-zone] [["2019-07-01T00:00:00Z" "UTC"]
                                        ["2019-01-01T00:00:00Z" "US/Pacific"]
                                        ["2019-07-01T00:00:00Z" "US/Pacific"]
                                        ["2019-07-01T13:14:15Z" "UTC"]
                                        ["2019-07-01T13:14:15Z" "US/Pacific"]]]
      (testing (format "system clock = %s; system timezone = %s" clock-instant clock-zone)
        (test-util/with-clock (t/mock-clock (t/instant clock-instant) clock-zone)
          (testing (format "\nshift %s '%s' to timezone ID '%s'" (.getName (class t)) t zone)
            (is (= expected
                   (date/with-time-zone-same-instant t (t/zone-id zone)))))))))
  (testing "can handle infinity dates (metabase/metabase#12761)"
    (is (date/with-time-zone-same-instant java.time.OffsetDateTime/MAX (t/zone-id "UTC")))
    (is (date/with-time-zone-same-instant java.time.OffsetDateTime/MIN (t/zone-id "UTC")))))

(deftest ^:parallel standard-offset-test
  (testing "standard-offset works correctly, regardless of system clock timezone"
    (doseq [clk [#second-date/t "2021-01-01T00:00-06:00[US/Central]"   ; one in CST
                 #second-date/t "2021-07-01T00:00-05:00[US/Central]"]] ; one in CDT
      (test-util/with-clock clk
        (is (= (t/zone-offset "-06:00") (common/standard-offset (t/zone-id "America/Chicago"))))
        (is (= (t/zone-offset "Z") (common/standard-offset (t/zone-id "UTC"))))))))
