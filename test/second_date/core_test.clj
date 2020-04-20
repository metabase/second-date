(ns second-date.core-test
  (:require [clojure.test :refer :all]
            [java-time :as t]
            [second-date.core :as second-date]))

;; TODO - more tests!
(deftest format-test
  (testing "ZonedDateTime"
    (testing "should get formatted as the same way as an OffsetDateTime"
      (is (= "2019-11-01T18:39:00-07:00"
             (second-date/format (t/zoned-date-time "2019-11-01T18:39:00-07:00[US/Pacific]")))))
    (testing "make sure it can handle different DST offsets correctly"
      (is (= "2020-02-13T16:31:00-08:00"
             (second-date/format (t/zoned-date-time "2020-02-13T16:31:00-08:00[US/Pacific]"))))))
  (testing "Instant"
    (is (= "1970-01-01T00:00:00Z"
           (second-date/format (t/instant "1970-01-01T00:00:00Z")))))
  (testing "nil"
    (is (= nil
           (second-date/format nil))
        "Passing `nil` should return `nil`")))

(deftest format-sql-test
  (testing "LocalDateTime"
    (is (= "2019-11-05 19:27:00"
           (second-date/format-sql (t/local-date-time "2019-11-05T19:27")))))
  (testing "ZonedDateTime"
    (is (= "2019-11-01 18:39:00-07:00"
           (second-date/format-sql (t/zoned-date-time "2019-11-01T18:39:00-07:00[US/Pacific]")))
        "should get formatted as the same way as an OffsetDateTime")))

(deftest adjuster-test
  (let [now (t/zoned-date-time "2019-12-10T17:17:00-08:00[US/Pacific]")]
    (testing "adjust temporal value to first day of week (Sunday)"
      (is (= (t/zoned-date-time "2019-12-08T17:17-08:00[US/Pacific]")
             (t/adjust now (second-date/adjuster :first-day-of-week)))))
    (testing "adjust temporal value to first day of ISO week (Monday)"
      (is (= (t/zoned-date-time "2019-12-09T17:17-08:00[US/Pacific]")
             (t/adjust now (second-date/adjuster :first-day-of-iso-week)))))
    (testing "adjust temporal value to first day of first week of year (previous or same Sunday as first day of year)"
      (is (= (t/zoned-date-time "2018-12-30T17:17-08:00[US/Pacific]")
             (t/adjust now (second-date/adjuster :first-week-of-year))
             (t/adjust now (second-date/adjuster :week-of-year 1)))))
    (testing "adjust temporal value to the 50th week of the year"
      (is (= (t/zoned-date-time "2019-12-08T17:17-08:00[US/Pacific]")
             (t/adjust now (second-date/adjuster :week-of-year 50)))))))

(deftest extract-test
  (testing "second-date/extract with 2 args"
    ;; everything is at `Sunday October 27th 2019 2:03:40.555 PM` or subset thereof
    (let [temporal-category->sample-values {:dates     [(t/local-date 2019 10 27)]
                                            :times     [(t/local-time  14 3 40 (* 555 1000000))
                                                        (t/offset-time 14 3 40 (* 555 1000000) (t/zone-offset -7))]
                                            :datetimes [(t/offset-date-time 2019 10 27 14 3 40 (* 555 1000000) (t/zone-offset -7))
                                                        (t/zoned-date-time  2019 10 27 14 3 40 (* 555 1000000) (t/zone-id "America/Los_Angeles"))]}]
      (doseq [[categories unit->expected] {#{:times :datetimes} {:minute-of-hour 3
                                                                 :hour-of-day    14}
                                           #{:dates :datetimes} {:day-of-week      1
                                                                 :iso-day-of-week  7
                                                                 :day-of-month     27
                                                                 :day-of-year      300
                                                                 :week-of-year     44
                                                                 :iso-week-of-year 43
                                                                 :month-of-year    10
                                                                 :quarter-of-year  4
                                                                 :year             2019}}
              category                    categories
              t                           (get temporal-category->sample-values category)
              [unit expected]             unit->expected]
        (is (= expected
               (second-date/extract t unit))
            (format "Extract %s from %s %s should be %s" unit (class t) t expected)))))
  (testing "second-date/extract with 1 arg (extract from now)"
    (is (= 2
           (t/with-clock (t/mock-clock (t/instant "2019-11-18T22:31:00Z") (t/zone-id "UTC"))
             (second-date/extract :day-of-week))))))

(deftest truncate-test
  (testing "second-date/truncate with 2 args"
    (let [t->unit->expected
          {(t/local-date 2019 10 27)
           {:second   (t/local-date 2019 10 27)
            :minute   (t/local-date 2019 10 27)
            :hour     (t/local-date 2019 10 27)
            :day      (t/local-date 2019 10 27)
            :week     (t/local-date 2019 10 27)
            :iso-week (t/local-date 2019 10 21)
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
            :iso-week (t/offset-date-time 2019 10 21 0  0  0 0 (t/zone-offset -7))
            :month    (t/offset-date-time 2019 10  1 0  0  0 0 (t/zone-offset -7))
            :quarter  (t/offset-date-time 2019 10  1 0  0  0 0 (t/zone-offset -7))
            :year     (t/offset-date-time 2019  1  1 0  0  0 0 (t/zone-offset -7))}

           (t/zoned-date-time  2019 10 27 14 3 40 (* 555 1000000) (t/zone-id "America/Los_Angeles"))
           {:second   (t/zoned-date-time  2019 10 27 14 3 40 0 (t/zone-id "America/Los_Angeles"))
            :minute   (t/zoned-date-time  2019 10 27 14 3  0 0 (t/zone-id "America/Los_Angeles"))
            :hour     (t/zoned-date-time  2019 10 27 14 0  0 0 (t/zone-id "America/Los_Angeles"))
            :day      (t/zoned-date-time  2019 10 27  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :week     (t/zoned-date-time  2019 10 27  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :iso-week (t/zoned-date-time  2019 10 21  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :month    (t/zoned-date-time  2019 10  1  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :quarter  (t/zoned-date-time  2019 10  1  0 0  0 0 (t/zone-id "America/Los_Angeles"))
            :year     (t/zoned-date-time  2019  1  1  0 0  0 0 (t/zone-id "America/Los_Angeles"))}}]
      (doseq [[t unit->expected] t->unit->expected
              [unit expected]    unit->expected]
        (is (= expected
               (second-date/truncate t unit))
            (format "Truncate %s %s to %s should be %s" (class t) t unit expected)))))
  (testing "second-date/truncate with 1 arg (truncate now)"
    (is (= (t/zoned-date-time "2019-11-18T00:00Z[UTC]")
           (t/with-clock (t/mock-clock (t/instant "2019-11-18T22:31:00Z") (t/zone-id "UTC"))
             (second-date/truncate :day))))))

(deftest add-test
  (testing "with 2 args (datetime relative to now)"
    (is (= (t/zoned-date-time "2019-11-20T22:31Z[UTC]")
           (t/with-clock (t/mock-clock (t/instant "2019-11-18T22:31:00Z") (t/zone-id "UTC"))
             (second-date/add :day 2)))))

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
               (second-date/add t unit n))
            (format "%s plus %d %ss should be %s" t n unit expected))))))

(deftest range-test
  (testing "with 1 arg (range relative to now)"
    (is (= {:start (t/zoned-date-time "2019-11-17T00:00Z[UTC]")
            :end   (t/zoned-date-time "2019-11-24T00:00Z[UTC]")}
           (t/with-clock (t/mock-clock (t/instant "2019-11-18T22:31:00Z") (t/zone-id "UTC"))
             (second-date/range :week)))))

  (testing "with 2 args"
    (is (= {:start (t/zoned-date-time "2019-10-27T00:00Z[UTC]")
            :end   (t/zoned-date-time "2019-11-03T00:00Z[UTC]")}
           (second-date/range (t/zoned-date-time "2019-11-01T15:29:00Z[UTC]") :week))))

  (testing "with 3 args (start/end inclusitivity options)"
    (testing "exclusive start"
      (is (= {:start (t/local-date-time "2019-10-31T23:59:59.999"), :end (t/local-date-time "2019-12-01T00:00")}
             (second-date/range (t/local-date-time "2019-11-18T00:00") :month {:start :exclusive}))))
    (testing "inclusive end"
      (is (= {:start (t/local-date-time "2019-11-01T00:00"), :end (t/local-date-time "2019-11-30T23:59:59.999")}
             (second-date/range (t/local-date-time "2019-11-18T00:00") :month {:end :inclusive}))))
    (testing ":day resolution + inclusive end"
      (is (= {:start (t/local-date "2019-11-01"), :end (t/local-date "2019-11-30")}
             (second-date/range (t/local-date "2019-11-18") :month {:end :inclusive, :resolution :day}))))))

(deftest comparison-range-test
  (testing "Comparing MONTH"
    (letfn [(comparison-range [comparison-type options]
              (second-date/comparison-range (t/local-date "2019-11-18") :month comparison-type (merge {:resolution :day} options)))]
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
              (second-date/comparison-range (t/local-date-time "2019-11-18T12:00") :day comparison-type (merge {:resolution :minute} options)))]
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

(deftest period-duration-test
  (testing "Creating a period duration from a string"
    (is (= (org.threeten.extra.PeriodDuration/of (t/duration "PT59S"))
           (second-date/period-duration "PT59S"))))
  (testing "Creating a period duration out of two temporal types of the same class"
    (is (= (second-date/period-duration "PT1S")
           (second-date/period-duration (t/offset-date-time "2019-12-03T02:30:05Z") (t/offset-date-time "2019-12-03T02:30:06Z")))))
  (testing "Creating a period duration out of two different temporal types"
    (is (= (second-date/period-duration "PT59S")
           (second-date/period-duration (t/instant "2019-12-03T02:30:27Z") (t/offset-date-time "2019-12-03T02:31:26Z"))))))

(deftest older-than-test
  (let [now (t/instant "2019-12-04T00:45:00Z")]
    (t/with-clock (t/mock-clock now (t/zone-id "America/Los_Angeles"))
      (testing (str "now = " now)
        (doseq [t ((juxt t/instant t/local-date t/local-date-time t/offset-date-time identity)
                   (t/zoned-date-time "2019-11-01T00:00-08:00[US/Pacific]"))]
          (testing (format "t = %s" (pr-str t))
            (is (= true
                   (second-date/older-than? t (t/weeks 2)))
                (format "%s happened before 2019-11-19" (pr-str t)))
            (is (= false
                   (second-date/older-than? t (t/months 2)))
                (format "%s did not happen before 2019-10-03" (pr-str t)))))))))
