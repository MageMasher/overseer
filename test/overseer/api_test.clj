(ns overseer.api-test
 (:require [clojure.test :refer :all]
           loom.graph
           [taoensso.timbre :as timbre]
           (overseer
             [core :as core]
             [api :as api]
             [test-utils :as test-utils]
             [executor :as exc])))

(deftest test-simple-graph
  (let [j0 (test-utils/job)
        j1 (test-utils/job)
        graph (api/simple-graph j0 j1)]
    (is (satisfies? loom.graph/Digraph graph))
    (is (= #{j0 j1} (set (loom.graph/nodes graph))))
    (is (empty? (loom.graph/edges graph)))))

(deftest test-harness
  (let [state (atom 0)
        job {:foo "bar"}
        wrapper
        (fn [f]
          (fn [job]
            (swap! state inc)
            (f job)))]
    (testing "function handler"
      (let [handler (fn [job]
                      (swap! state inc)
                      :quux)
            harnessed (api/harness handler wrapper)]
        (reset! state 0)
        (is (= :quux (exc/invoke-handler harnessed job)))
        (is (= 2 @state))
        (is (map? harnessed))))

    (testing "mapping function handler"
      (let [handler (fn [job]
                      (swap! state inc)
                      :quux)
            harnessed (api/harness handler :pre-process wrapper)]
        (reset! state 0)
        (is (= :quux (exc/invoke-handler harnessed job)))
        (is (= 2 @state))
        (is (= #{:pre-process :process} (set (keys harnessed))))))

    (testing "map handler - :process"
      (let [handler {:process (fn [job] (swap! state inc))}
            harnessed (api/harness handler wrapper)]
        (reset! state 0)
        (exc/invoke-handler harnessed job)
        (is (= 2 @state))))

    (testing "map handler - :pre-process"
      (let [handler {:pre-process (fn [job] (swap! state inc))
                     :process (fn [job] job)}
            harnessed (api/harness handler :pre-process wrapper)]
        (reset! state 0)
        (exc/invoke-handler harnessed job)
        (is (= 2 @state))))

    (testing "map handler - :post-process"
      (let [post-wrapper
            (fn [f]
              (fn [job res]
                (swap! state inc)
                (f job res)))
            handler {:process (fn [job] :quux)
                     :post-process (fn [job res]
                                     (swap! state inc)
                                     res)}
            harnessed (api/harness handler :post-process post-wrapper)]
        (reset! state 0)
        (is (= :quux (exc/invoke-handler harnessed job)))
        (is (= 2 @state))))

    (testing "harnessing missing keys"
      (let [handler {:process (fn [job] (:foo job))}
            post-wrapper (fn [f]
                           (fn [job res]
                             (swap! state inc)))
            post-harnessed (api/harness handler :post-process post-wrapper)]
        (reset! state 0)
        (is (= 0 @state))
        (exc/invoke-handler post-harnessed job)
        (is (= 1 @state))))))

(deftest test-fault
  (timbre/with-log-level :report
    (let [config {}
          store (test-utils/datomic-store)
          job-ran? (atom false)
          job-handlers {:bar (fn [job]
                               (reset! job-ran? true)
                               (api/fault "transient problem occurred"))}
          {job-id :job/id :as job} (test-utils/job {:job/type :bar})]
      (core/transact-graph store (api/simple-graph job))
      (is (= :unstarted (:job/status (core/job-info store job-id))))
      (core/reserve-job store job-id)
      (exc/run-job config store job-handlers job)
      (is @job-ran?)
      (is (= :unstarted (:job/status (core/job-info store job-id)))))))
