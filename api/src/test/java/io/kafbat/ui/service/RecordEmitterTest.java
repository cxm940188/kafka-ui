package io.kafbat.ui.service;

import static io.kafbat.ui.model.PollingModeDTO.EARLIEST;
import static io.kafbat.ui.model.PollingModeDTO.FROM_OFFSET;
import static io.kafbat.ui.model.PollingModeDTO.FROM_TIMESTAMP;
import static io.kafbat.ui.model.PollingModeDTO.LATEST;
import static io.kafbat.ui.model.PollingModeDTO.TO_OFFSET;
import static io.kafbat.ui.model.PollingModeDTO.TO_TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;

import io.kafbat.ui.AbstractIntegrationTest;
import io.kafbat.ui.emitter.BackwardEmitter;
import io.kafbat.ui.emitter.Cursor;
import io.kafbat.ui.emitter.EnhancedConsumer;
import io.kafbat.ui.emitter.ForwardEmitter;
import io.kafbat.ui.emitter.PollingSettings;
import io.kafbat.ui.emitter.PollingThrottler;
import io.kafbat.ui.model.ConsumerPosition;
import io.kafbat.ui.model.TopicMessageDTO;
import io.kafbat.ui.model.TopicMessageEventDTO;
import io.kafbat.ui.producer.KafkaTestProducer;
import io.kafbat.ui.serde.api.Serde;
import io.kafbat.ui.serdes.ConsumerRecordDeserializer;
import io.kafbat.ui.serdes.PropertyResolverImpl;
import io.kafbat.ui.serdes.builtin.StringSerde;
import io.kafbat.ui.util.ApplicationMetrics;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.test.StepVerifier;

@Slf4j
class RecordEmitterTest extends AbstractIntegrationTest {

  static final int PARTITIONS = 5;
  static final int MSGS_PER_PARTITION = 100;

  static final String TOPIC = RecordEmitterTest.class.getSimpleName() + "_" + UUID.randomUUID();
  static final String EMPTY_TOPIC = TOPIC + "_empty";
  static final List<Record> SENT_RECORDS = new ArrayList<>();
  static final ConsumerRecordDeserializer RECORD_DESERIALIZER = createRecordsDeserializer();
  static final Cursor.Tracking CURSOR_MOCK = Mockito.mock(Cursor.Tracking.class);
  static final Predicate<TopicMessageDTO> NOOP_FILTER = m -> true;

  @BeforeAll
  static void generateMsgs() throws Exception {
    createTopic(new NewTopic(TOPIC, PARTITIONS, (short) 1));
    createTopic(new NewTopic(EMPTY_TOPIC, PARTITIONS, (short) 1));
    long startTs = System.currentTimeMillis();
    try (var producer = KafkaTestProducer.forKafka(kafka)) {
      for (int partition = 0; partition < PARTITIONS; partition++) {
        for (int i = 0; i < MSGS_PER_PARTITION; i++) {
          long ts = (startTs += 100);
          var value = "msg_" + partition + "_" + i;
          var metadata = producer.send(
              new ProducerRecord<>(
                  TOPIC, partition, ts, null, value, List.of(
                      new RecordHeader("name", null),
                      new RecordHeader("name2", "value".getBytes())
                  )
              )
          ).get();
          SENT_RECORDS.add(
              new Record(
                  value,
                  new TopicPartition(metadata.topic(), metadata.partition()),
                  metadata.offset(),
                  ts
              )
          );
        }
      }
    }
  }

  @AfterAll
  static void cleanup() {
    deleteTopic(TOPIC);
    deleteTopic(EMPTY_TOPIC);
    SENT_RECORDS.clear();
  }

  private static ConsumerRecordDeserializer createRecordsDeserializer() {
    Serde s = new StringSerde();
    s.configure(PropertyResolverImpl.empty(), PropertyResolverImpl.empty(), PropertyResolverImpl.empty());
    return new ConsumerRecordDeserializer(
        StringSerde.name(),
        s.deserializer(null, Serde.Target.KEY),
        StringSerde.name(),
        s.deserializer(null, Serde.Target.VALUE),
        StringSerde.name(),
        s.deserializer(null, Serde.Target.KEY),
        s.deserializer(null, Serde.Target.VALUE),
        msg -> msg
    );
  }

  @Test
  void pollNothingOnEmptyTopic() {
    var forwardEmitter = new ForwardEmitter(
        this::createConsumer,
        new ConsumerPosition(EARLIEST, EMPTY_TOPIC, List.of(), null, null),
        100,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    var backwardEmitter = new BackwardEmitter(
        this::createConsumer,
        new ConsumerPosition(EARLIEST, EMPTY_TOPIC, List.of(), null, null),
        100,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    StepVerifier.create(Flux.create(forwardEmitter))
        .expectNextMatches(m -> m.getType().equals(TopicMessageEventDTO.TypeEnum.PHASE))
        .expectNextMatches(m -> m.getType().equals(TopicMessageEventDTO.TypeEnum.DONE))
        .expectComplete()
        .verify();

    StepVerifier.create(Flux.create(backwardEmitter))
        .expectNextMatches(m -> m.getType().equals(TopicMessageEventDTO.TypeEnum.PHASE))
        .expectNextMatches(m -> m.getType().equals(TopicMessageEventDTO.TypeEnum.DONE))
        .expectComplete()
        .verify();
  }

  @Test
  void pollFullTopicFromBeginning() {
    var forwardEmitter = new ForwardEmitter(
        this::createConsumer,
        new ConsumerPosition(EARLIEST, TOPIC, List.of(), null, null),
        PARTITIONS * MSGS_PER_PARTITION,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    var backwardEmitter = new BackwardEmitter(
        this::createConsumer,
        new ConsumerPosition(LATEST, TOPIC, List.of(), null, null),
        PARTITIONS * MSGS_PER_PARTITION,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    List<String> expectedValues = SENT_RECORDS.stream().map(Record::getValue).collect(Collectors.toList());

    expectEmitter(forwardEmitter, expectedValues);
    expectEmitter(backwardEmitter, expectedValues);
  }

  @Test
  void pollWithOffsets() {
    Map<TopicPartition, Long> targetOffsets = new HashMap<>();
    for (int i = 0; i < PARTITIONS; i++) {
      long offset = ThreadLocalRandom.current().nextLong(MSGS_PER_PARTITION);
      targetOffsets.put(new TopicPartition(TOPIC, i), offset);
    }

    var forwardEmitter = new ForwardEmitter(
        this::createConsumer,
        new ConsumerPosition(FROM_OFFSET, TOPIC, List.copyOf(targetOffsets.keySet()), null,
            new ConsumerPosition.Offsets(null, targetOffsets)),
        PARTITIONS * MSGS_PER_PARTITION,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    var backwardEmitter = new BackwardEmitter(
        this::createConsumer,
        new ConsumerPosition(TO_OFFSET, TOPIC, List.copyOf(targetOffsets.keySet()), null,
            new ConsumerPosition.Offsets(null, targetOffsets)),
        PARTITIONS * MSGS_PER_PARTITION,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    var expectedValues = SENT_RECORDS.stream()
        .filter(r -> r.getOffset() >= targetOffsets.get(r.getTp()))
        .map(Record::getValue)
        .collect(Collectors.toList());

    expectEmitter(forwardEmitter, expectedValues);

    expectedValues = SENT_RECORDS.stream()
        .filter(r -> r.getOffset() < targetOffsets.get(r.getTp()))
        .map(Record::getValue)
        .collect(Collectors.toList());

    expectEmitter(backwardEmitter, expectedValues);
  }

  @Test
  void pollWithTimestamps() {
    var tsStats = SENT_RECORDS.stream().mapToLong(Record::getTimestamp).summaryStatistics();
    //choosing ts in the middle
    long targetTimestamp = tsStats.getMin() + ((tsStats.getMax() - tsStats.getMin()) / 2);

    var forwardEmitter = new ForwardEmitter(
        this::createConsumer,
        new ConsumerPosition(FROM_TIMESTAMP, TOPIC, List.of(), targetTimestamp, null),
        PARTITIONS * MSGS_PER_PARTITION,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    expectEmitter(
        forwardEmitter,
        SENT_RECORDS.stream()
            .filter(r -> r.getTimestamp() >= targetTimestamp)
            .map(Record::getValue)
            .collect(Collectors.toList())
    );

    var backwardEmitter = new BackwardEmitter(
        this::createConsumer,
        new ConsumerPosition(TO_TIMESTAMP, TOPIC, List.of(), targetTimestamp, null),
        PARTITIONS * MSGS_PER_PARTITION,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    expectEmitter(
        backwardEmitter,
        SENT_RECORDS.stream()
            .filter(r -> r.getTimestamp() < targetTimestamp)
            .map(Record::getValue)
            .collect(Collectors.toList())
    );
  }

  @Test
  void backwardEmitterSeekToEnd() {
    final int numMessages = 100;
    final Map<TopicPartition, Long> targetOffsets = new HashMap<>();
    for (int i = 0; i < PARTITIONS; i++) {
      targetOffsets.put(new TopicPartition(TOPIC, i), (long) MSGS_PER_PARTITION);
    }

    var backwardEmitter = new BackwardEmitter(
        this::createConsumer,
        new ConsumerPosition(TO_OFFSET, TOPIC, List.copyOf(targetOffsets.keySet()), null,
            new ConsumerPosition.Offsets(null, targetOffsets)),
        numMessages,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    var expectedValues = SENT_RECORDS.stream()
        .filter(r -> r.getOffset() < targetOffsets.get(r.getTp()))
        .filter(r -> r.getOffset() >= (targetOffsets.get(r.getTp()) - (numMessages / PARTITIONS)))
        .map(Record::getValue)
        .collect(Collectors.toList());

    assertThat(expectedValues).size().isEqualTo(numMessages);

    expectEmitter(backwardEmitter, expectedValues);
  }

  @Test
  void backwardEmitterSeekToBegin() {
    Map<TopicPartition, Long> offsets = new HashMap<>();
    for (int i = 0; i < PARTITIONS; i++) {
      offsets.put(new TopicPartition(TOPIC, i), 0L);
    }

    var backwardEmitter = new BackwardEmitter(
        this::createConsumer,
        new ConsumerPosition(TO_OFFSET, TOPIC, List.copyOf(offsets.keySet()), null,
            new ConsumerPosition.Offsets(null, offsets)),
        100,
        RECORD_DESERIALIZER,
        NOOP_FILTER,
        PollingSettings.createDefault(),
        CURSOR_MOCK
    );

    expectEmitter(backwardEmitter,
        100,
        e -> e.expectNextCount(0),
        StepVerifier.Assertions::hasNotDroppedElements
    );
  }

  private void expectEmitter(Consumer<FluxSink<TopicMessageEventDTO>> emitter, List<String> expectedValues) {
    expectEmitter(emitter,
        expectedValues.size(),
        e -> e.recordWith(ArrayList::new)
            .expectNextCount(expectedValues.size())
            .expectRecordedMatches(r -> r.containsAll(expectedValues))
            .consumeRecordedWith(r -> log.info("Collected collection: {}", r)),
        v -> {
        }
    );
  }

  private void expectEmitter(
      Consumer<FluxSink<TopicMessageEventDTO>> emitter,
      int take,
      Function<StepVerifier.Step<String>, StepVerifier.Step<String>> stepConsumer,
      Consumer<StepVerifier.Assertions> assertionsConsumer) {

    StepVerifier.FirstStep<String> firstStep = StepVerifier.create(
        Flux.create(emitter)
            .filter(m -> m.getType().equals(TopicMessageEventDTO.TypeEnum.MESSAGE))
            .take(take)
            .map(m -> m.getMessage().getValue())
    );

    StepVerifier.Step<String> step = stepConsumer.apply(firstStep);
    assertionsConsumer.accept(step.expectComplete().verifyThenAssertThat());
  }

  private EnhancedConsumer createConsumer() {
    return createConsumer(Map.of());
  }

  private EnhancedConsumer createConsumer(Map<String, Object> properties) {
    final Map<String, ? extends Serializable> map = Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 19 // to check multiple polls
    );
    Properties props = new Properties();
    props.putAll(map);
    props.putAll(properties);
    return new EnhancedConsumer(props, PollingThrottler.noop(), ApplicationMetrics.noop());
  }

  @Value
  static class Record {
    String value;
    TopicPartition tp;
    long offset;
    long timestamp;
  }
}
