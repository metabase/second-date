(ns second-date.common-test
  (:require
   [clojure.test :refer :all]
   [second-date.common :as common]
   [second-date.test-util :as test-util])
  (:import
   (java.util Locale)))

(set! *warn-on-reflection* true)

(deftest ^:parallel lower-case-en-test
  (is (= "id"
         (common/lower-case-en "ID"))))

(deftest ^:synchronized lower-case-en-turkish-test
  ;; TODO Can we achieve something like with-locale in CLJS?
  (test-util/with-locale "tr"
    (is (= "id"
           (common/lower-case-en "ID")))))

(deftest ^:parallel upper-case-en-test
  (is (= "ID"
         (common/upper-case-en "id"))))

(deftest ^:synchronized upper-case-en-turkish-test
  (test-util/with-locale "tr"
    (is (= "ID"
           (common/upper-case-en "id")))))

(deftest ^:parallel normalized-locale-string-test
  (doseq [[s expected] {"en"      "en"
                        "EN"      "en"
                        "En"      "en"
                        "en_US"   "en_US"
                        "en-US"   "en_US"
                        nil       nil
                        "en--"    nil
                        "a"       nil
                        "eng-USA" nil}]
    (testing (pr-str (list 'normalized-locale-string s))
      (is (= expected
             (common/normalized-locale-string s))))))

(deftest ^:parallel locale-test
  (testing "Should be able to coerce various types of objects to Locales"
    (doseq [arg-type [:str :keyword]
            country   ["en" "En" "EN"]
            language  ["us" "Us" "US" nil]
            separator (when language
                        (concat ["_" "-"] (when (= arg-type :keyword) ["/"])))
            :let      [s (str country (when language (str separator language)))
                       x (case arg-type
                           :str     s
                           :keyword (keyword s))]]
      (testing (pr-str (list 'locale x))
        (is (= (Locale/forLanguageTag (if language "en-US" "en"))
               (common/locale x)))))

    (testing "If something is already a Locale, `locale` should act as an identity fn"
      (is (= (Locale/forLanguageTag "en-US")
             (common/locale (Locale/forLanguageTag "en-US"))))))
  (testing "nil"
    (is (= nil
           (common/locale nil)))))
