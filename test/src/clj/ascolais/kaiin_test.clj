(ns ascolais.kaiin-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.kaiin :as kaiin]))

(deftest greet-test
  (testing "greet returns a greeting message"
    (is (= "Hello, World!" (kaiin/greet "World")))
    (is (= "Hello, Clojure!" (kaiin/greet "Clojure")))))
