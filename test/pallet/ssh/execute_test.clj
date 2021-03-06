(ns pallet.ssh.execute-test
  (:require
   [clojure.string :refer [trim]]
   [clojure.test :refer :all]
   [clojure.tools.logging :refer [debugf]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture
                                           with-log-to-string]]
   [pallet.compute.node-list :refer [make-localhost-node]]
   [pallet.core.api-impl :refer [with-script-for-node]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.ssh.execute
    :refer [get-connection ssh-script-on-target with-connection]]
   [pallet.transport :as transport]))

(def release-connection #'pallet.ssh.execute/release-connection)

(use-fixtures :once (logging-threshold-fixture))

(def open-channel clj-ssh.ssh/open-channel)

(defn- get-cached-connection [session]
  (let [connection (get-connection session)]
    (release-connection session connection)
    connection))

(deftest with-connection-test
  (let [session {:server {:node (make-localhost-node)
                          :image {:os-family :ubuntu}}
                 :user *admin-user*}]
    (testing "default"
      (with-connection session
        [connection]
        (is connection)))
    (testing "caching"
      (let [original-connection (get-cached-connection session)]
        (with-connection session
          [connection]
          (is connection)
          (is (= original-connection connection) "connection cached"))
        (is (= original-connection (get-connection session))
            "connection still cached")
        (is (transport/open? (get-connection session)))))
    (testing "fail on general open-channel exception"
      (let [log-out
            (with-log-to-string []
              (with-redefs [clj-ssh.ssh/open-channel
                            (fn [session session-type]
                              (throw
                               (ex-info
                                (format
                                 "clj-ssh open-channel failure: %s" "something")
                                {:type :clj-ssh/open-channel-failure
                                 :reason :clj-ssh/unknown}
                                (com.jcraft.jsch.JSchException.
                                 "something"))))]
                (try
                  (with-connection session
                    [connection]
                    (transport/exec connection {:execv ["echo" "1"]} {})
                    (is nil))           ; should never get here
                  (is nil)              ; should never get here
                  (catch Exception e
                    (is (instance? com.jcraft.jsch.JSchException (.getCause e)))
                    (is (= :clj-ssh/unknown (:reason (ex-data e))))
                    (is (= :clj-ssh/open-channel-failure
                           (:type (ex-data e))))))))]
        (is log-out)))
    (testing "retry on session is not opened open-channel exception"
      (let [no-throw-after-first-call (atom nil)
            seen (atom nil)
            open-channel-count (atom 0)
            original-connection (get-cached-connection session)
            log-out
            (with-log-to-string []
              (with-redefs [clj-ssh.ssh/open-channel
                            (fn [session session-type]
                              (swap! open-channel-count inc)
                              (debugf (Exception. "here") "Inc")
                              (if @no-throw-after-first-call
                                (open-channel session session-type)
                                (do
                                  (reset! no-throw-after-first-call true)
                                  (throw
                                   (ex-info
                                    (format "clj-ssh open-channel failure")
                                    {:type :clj-ssh/open-channel-failure
                                     :reason :clj-ssh/channel-open-failed}
                                    (com.jcraft.jsch.JSchException.
                                     "some exception"))))))]
                (with-connection session
                  [connection]
                  (transport/exec connection {:execv ["echo" "1"]} {})
                  (reset! seen true))
                (is @no-throw-after-first-call "called at least once")
                (is @seen "called at least twice")
                (is (= 3 @open-channel-count) "1 failed + sftp + exec")
                (is (not= original-connection (get-connection session))
                    "new cached connection")))]
        (is log-out "exception is logged")))
    (testing "new session after :new-login-after-action"
      (with-script-for-node (:server session) nil
        (let [original-connection (get-cached-connection session)]
          (ssh-script-on-target
           session {:node-value-path (keyword (name (gensym "nv")))}
           nil [{} "echo 1"])
          (is (= original-connection (get-cached-connection session)))
          (ssh-script-on-target
           session {:node-value-path (keyword (name (gensym "nv")))
                    :new-login-after-action true}
           nil [{} "echo 1"])
          (is (not= original-connection (get-cached-connection session))
              "a new connection following :new-login-after-action")
          (let [second-connection (get-cached-connection session)]
            (ssh-script-on-target
             session {:node-value-path (keyword (name (gensym "nv")))}
             nil [{} "echo 1"])
            (is (not= original-connection (get-cached-connection session))
                "a new connection following :new-login-after-action")
            (is (= second-connection (get-cached-connection session))
                "a new connection is stable following :new-login-after-action")))))
    (testing "agent-forward"
      (with-script-for-node (:server session) nil
        (let [[r s] (ssh-script-on-target
                     session {:node-value-path (keyword (name (gensym "nv")))}
                     nil [{} "echo $SSH_AUTH_SOCK"])]
          (is (= "" (trim (:out r)))))
        (let [[r s] (ssh-script-on-target
                     session {:node-value-path (keyword (name (gensym "nv")))
                              :ssh-agent-forwarding true}
                     nil [{} "echo $SSH_AUTH_SOCK"])]
          (is (not= "" (trim (:out r)))))))
    (testing "env"
      (with-script-for-node (:server session) nil
        (let [[r s] (ssh-script-on-target
                     session {:node-value-path (keyword (name (gensym "nv")))}
                     nil [{} "echo $XXX"])]
          (is (= "" (trim (:out r)))))
        (let [[r s] (ssh-script-on-target
                     session {:node-value-path (keyword (name (gensym "nv")))
                              :script-env {:XXX "abcd"}}
                     nil [{} "echo $XXX"])]
          (is (= "abcd" (trim (:out r)))))))
    (testing "env-fwd"
      (with-script-for-node (:server session) nil
        (let [[r s] (ssh-script-on-target
                     session {:node-value-path (keyword (name (gensym "nv")))
                              :script-env-fwd [:SSH_CLIENT]}
                     nil [{} "echo $SSH_CLIENT"])]
          (is (= "127.0.0.1" (subs (:out r) 0 9))))))))

(deftest ^:require-no-ssh-env with-connection-no-ssh-env-test
  (let [session {:server {:node (make-localhost-node)
                          :image {:os-family :ubuntu}}
                 :user *admin-user*}]
    (testing "env-fwd"
      (with-script-for-node (:server session) nil
        (let [[r s] (ssh-script-on-target
                     session {:node-value-path (keyword (name (gensym "nv")))}
                     nil [{} "echo $SSH_CLIENT"])]
          (is (= "" (trim (:out r)))))))))
