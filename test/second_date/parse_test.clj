(ns second-date.parse-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [java-time :as t]
            [second-date.parse :as parse]
            [second-date.test :as tu]))

(deftest parse-test
  ;; system timezone should not affect the way strings are parsed
  (doseq [system-timezone-id ["UTC" "US/Pacific"]]
    (tu/with-system-timezone-id system-timezone-id
      (letfn [(message [expected s default-timezone-id]
                (if default-timezone-id
                  (format "parsing '%s' with default timezone id '%s' should give you %s" s default-timezone-id (pr-str expected))
                  (format "parsing '%s' should give you %s" s (pr-str expected))))
              (is-parsed? [expected s default-timezone-id]
                {:pre [(string? s)]}
                (testing "ISO-8601-style literal"
                  (is (= expected
                         (parse/parse s default-timezone-id))
                      (message expected s default-timezone-id)))
                (when (str/includes? s "T")
                  (testing "SQL-style literal"
                    (let [s (str/replace s #"T" " ")]
                      (is (= expected
                             (parse/parse s default-timezone-id))
                          (message expected s default-timezone-id))
                      (when-let [[_ before-offset offset] (re-find #"(.*)((?:(?:[+-]\d{2}:\d{2})|Z).*$)" s)]
                        (let [s (format "%s %s" before-offset offset)]
                          (testing "w/ space before offset"
                            (is (= expected
                                   (parse/parse s default-timezone-id))
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
                 (parse/parse "2014-08-01 10:00:00.000 -0700")))
          (is (= (t/offset-date-time "2014-08-01T10:00-07:00")
                 (parse/parse "2014-08-01 10:00:00 -0700")))
          (is (= (t/offset-date-time "2014-08-01T10:00-07:00")
                 (parse/parse "2014-08-01 10:00 -0700"))))
        (testing "Should be able to parse SQL-style literals where Zone ID is separated by a space, without brackets"
          (is (= (t/zoned-date-time "2014-08-01T10:00Z[UTC]")
                 (parse/parse "2014-08-01 10:00:00.000 UTC")))
          (is (= (t/zoned-date-time "2014-08-02T00:00+08:00[Asia/Hong_Kong]")
                 (parse/parse "2014-08-02 00:00:00.000 Asia/Hong_Kong"))))
        (testing "Should be able to parse strings with hour-only offsets e.g. '+00'"
          (is (= (t/offset-time "07:23:18.331Z")
                 (parse/parse "07:23:18.331-00")))
          (is (= (t/offset-time "07:23:18.000Z")
                 (parse/parse "07:23:18-00")))
          (is (= (t/offset-time "07:23:00.000Z")
                 (parse/parse "07:23-00")))
          (is (= (t/offset-time "07:23:18.331-08:00")
                 (parse/parse "07:23:18.331-08")))
          (is (= (t/offset-time "07:23:18.000-08:00")
                 (parse/parse "07:23:18-08")))
          (is (= (t/offset-time "07:23:00.000-08:00")
                 (parse/parse "07:23-08")))))
      (testing "nil"
        (is (= nil
               (parse/parse nil))
            "Passing `nil` should return `nil`"))
      (testing "blank strings"
        (is (= nil
               (parse/parse ""))
            (= nil
               (parse/parse "   ")))))))
