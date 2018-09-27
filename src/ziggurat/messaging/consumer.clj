(ns ziggurat.messaging.consumer
  (:require [clojure.tools.logging :as log]
            [langohr.basic :as lb]
            [langohr.channel :as lch]
            [langohr.consumers :as lcons]
            [sentry.core :as sentry]
            [taoensso.nippy :as nippy]
            [ziggurat.config :refer [get-in-config]]
            [ziggurat.mapper :as mpr]
            [ziggurat.messaging.connection :refer [connection]]
            [ziggurat.sentry :refer [sentry-reporter]]
            [ziggurat.messaging.util :refer :all])
  (:import [com.rabbitmq.client AlreadyClosedException Channel]))

(defn convert-and-ack-message
  "Take the ch metadata payload and ack? as parameter.
   Decodes the payload the ack it if ack is enabled and returns the message"
  [ch {:keys [delivery-tag] :as meta} ^bytes payload ack?]
  (try
    (let [message (nippy/thaw payload)]
      (log/debug "Calling mapper fn with the message - " message " with retry count - " (:retry-count message))
      (when ack?
        (lb/ack ch delivery-tag))
      message)
    (catch Exception e
      (sentry/report-error sentry-reporter e "Error while decoding message")
      (lb/reject ch delivery-tag false)
      nil)))

(defn- try-consuming-dead-set-messages [ch ack? queue-name]
  (try
    (let [[meta payload] (lb/get ch queue-name false)]
      (when (some? payload)
        (convert-and-ack-message ch meta payload ack?)))
    (catch Exception e
      (sentry/report-error sentry-reporter e "Error while consuming the dead set message"))))

(defn get-dead-set-messages
  "Get the n(count) messages from the rabbitmq.

   If ack is set to true,
   then ack all the messages while consuming and make them unavailable to other subscribers.

   If ack is false,
   it will not ack the message."
  [ack? topic-entity count]
  (remove nil?
          (with-open [ch (lch/open connection)]
            (doall (for [_ (range count)]
                     (try-consuming-dead-set-messages ch
                                                      ack?
                                                      (prefixed-queue-name topic-entity
                                                                           (get-in-config [:rabbit-mq :dead-letter :queue-name]))))))))

(defn- message-handler [wrapped-mapper-fn topic-entity]
  (fn [ch meta ^bytes payload]
    (if-let [message (convert-and-ack-message ch meta payload true)]
      (wrapped-mapper-fn message topic-entity))))

(defn close [^Channel channel]
  (try
    (.close channel)
    (catch AlreadyClosedException _
      nil)))

(defn- start-subscriber* [ch prefetch-count queue-name wrapped-mapper-fn topic-entity]
  (lb/qos ch prefetch-count)
  (let [consumer-tag (lcons/subscribe ch
                                      queue-name
                                      (message-handler wrapped-mapper-fn topic-entity)
                                      {:handle-shutdown-signal-fn (fn [consumer_tag reason]
                                                                    (log/info "Closing channel with consumer tag - " consumer_tag)
                                                                    (close ch))})]

    (log/info "starting consumer for instant-queue with consumer tag - " consumer-tag)))

(defn start-retry-subscriber* [ch mapper-fn topic-entity]
  (start-subscriber* ch
                     (get-in-config [:jobs :instant :prefetch-count])
                     (prefixed-queue-name topic-entity (get-in-config [:rabbit-mq :instant :queue-name]))
                     (mpr/mapper-func mapper-fn)
                     topic-entity))

(defn start-channels-subscriber* [channels ch topic-entity-name]
  (doseq [channel channels]
    (let [channel-key (-> channel first)
          channel-handler-fn (-> channel second)]
      (start-subscriber* ch
                         (get-in-config [:jobs :instant :prefetch-count])
                         (prefixed-channel-name topic-entity-name channel-key (get-in-config [:rabbit-mq :instant :queue-name]))
                         (mpr/channel-mapper-func channel-handler-fn channel-key)
                         topic-entity-name))))

(defn start-subscribers
  "Starts the subscriber to the instant queue of the rabbitmq"
  [stream-routes]
  (when (get-in-config [:retry :enabled])
    (dotimes [_ (get-in-config [:jobs :instant :worker-count])]
      (doseq [stream-route stream-routes]
        (let [rmq-channel (lch/open connection)
              topic-entity (-> stream-route first name)
              topic-handler (-> stream-route second :handler-fn)
              channels (-> stream-route second (dissoc :handler-fn))]
          (start-channels-subscriber* channels rmq-channel topic-entity)
          (start-retry-subscriber* rmq-channel topic-handler topic-entity))))))