(ns second-date.test-util
  (:require
   [clojure.test :refer :all]
   [java-time.api :as t])
  (:import
   (java.util Locale TimeZone)))

(set! *warn-on-reflection* true)

(defn -do-with-system-timezone-id
  "Implementation for [[with-system-timezone-id]]."
  [^String timezone-id thunk]
  ;; skip all the property changes if the system timezone doesn't need to be changed.
  (let [original-timezone        (TimeZone/getDefault)
        original-system-property (System/getProperty "user.timezone")
        new-timezone             (TimeZone/getTimeZone timezone-id)]
    (if (and (= original-timezone new-timezone)
             (= original-system-property timezone-id))
      (thunk)
      (do
        (when ((loaded-libs) 'mb.hawk.parallel)
          ((resolve 'mb.hawk.parallel/assert-test-is-not-parallel) `with-system-timezone-id))
        (try
          (TimeZone/setDefault new-timezone)
          (System/setProperty "user.timezone" timezone-id)
          (testing (format "JVM timezone set to %s" timezone-id)
            (thunk))
          (finally
            (TimeZone/setDefault original-timezone)
            (System/setProperty "user.timezone" original-system-property)))))))

(defmacro with-system-timezone-id
  "Execute `body` with the system time zone temporarily changed to the time zone named by `timezone-id`."
  [timezone-id & body]
  `(-do-with-system-timezone-id ~timezone-id (^:once fn* [] ~@body)))

(defn -do-with-clock
  "Implementation for [[with-clock]]."
  [clock thunk]
  (testing (format "\nsystem clock = %s" (pr-str clock))
    (let [clock (cond
                  (t/clock? clock)           clock
                  (t/zoned-date-time? clock) (t/mock-clock (t/instant clock) (t/zone-id clock))
                  :else                      (throw (Exception. (format "Invalid clock: ^%s %s"
                                                                        (.getName (class clock))
                                                                        (pr-str clock)))))]
      (t/with-clock clock
        (thunk)))))

(defmacro with-clock
  "Same as [[t/with-clock]], but adds [[testing]] context, and also supports using `ZonedDateTime` instances
  directly (converting them to a mock clock automatically).

    (mt/with-clock #t \"2019-12-10T00:00-08:00[US/Pacific]\"
      ...)"
  [clock & body]
  `(-do-with-clock ~clock (fn [] ~@body)))

(defn -do-with-locale
  "Sets the default locale temporarily to `locale-tag`, then invokes `f` and reverts the locale change"
  [locale-tag f]
  (when ((loaded-libs) 'mb.hawk.parallel)
    ((resolve 'mb.hawk.parallel/assert-test-is-not-parallel) `with-locale))
  (let [current-locale (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag locale-tag))
      (f)
      (finally
        (Locale/setDefault current-locale)))))

(defmacro with-locale
  "Allows a test to override the locale temporarily"
  [locale-tag & body]
  `(-do-with-locale ~locale-tag (fn [] ~@body)))
