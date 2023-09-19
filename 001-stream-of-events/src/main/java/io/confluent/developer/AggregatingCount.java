package io.confluent.developer;

import io.confluent.common.utils.TestUtils;
import io.confluent.developer.avro.TicketSale;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Produced;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class AggregatingCount {

	private SpecificAvroSerde<TicketSale> ticketSaleSerde(final Properties allProps) {
		final SpecificAvroSerde<TicketSale> serde = new SpecificAvroSerde<>();
		Map<String, String> config = (Map) allProps;
		serde.configure(config, false);
		return serde;
	}

	public Topology buildTopology(Properties allProps, final SpecificAvroSerde<TicketSale> ticketSaleSerde) {
		final StreamsBuilder streamsBuilder = new StreamsBuilder();

		final String inputTopic = allProps.getProperty("input.topic.name");
		final String outputTopic = allProps.getProperty("output.topic.name");

		streamsBuilder.stream(inputTopic, Consumed.with(Serdes.String(), ticketSaleSerde))
			.map((k ,v) -> new KeyValue<>(v.getTitle(), v.getTicketTotalValue()))
			.groupByKey(Grouped.with(Serdes.String(), Serdes.Integer()))
			.count()
			.toStream()
			.mapValues(v -> v.toString() + " tickets sold")
			.to(outputTopic, Produced.with(Serdes.String(), Serdes.String()));

		return streamsBuilder.build();
	}

	public void createTopics(Properties allProps) {
		AdminClient client = AdminClient.create(allProps);

		List<NewTopic> topics = new ArrayList<>();
		topics.add(new NewTopic(
			allProps.getProperty("input.topic.name"),
			Integer.parseInt(allProps.getProperty("input.topic.partitions")),
			Short.parseShort(allProps.getProperty("input.topic.replication.factor"))));
		topics.add(new NewTopic(
			allProps.getProperty("output.topic.name"),
			Integer.parseInt(allProps.getProperty("output.topic.partitions")),
			Short.parseShort(allProps.getProperty("output.topic.replication.factor"))));

		client.createTopics(topics);
		client.close();
	}

	public Properties loadEnvProperties(String fileName) throws IOException {
		Properties allProps = new Properties();
		FileInputStream input = new FileInputStream(fileName);
		allProps.load(input);
		input.close();

		return allProps;
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			throw new IllegalArgumentException(
				"This program takes one argument: the path to an environment configuration file.");
		}

		new AggregatingCount().runRecipe(args[0]);
	}

	private void runRecipe(final String configPath) throws IOException {
		final Properties allProps = new Properties();

		try (InputStream inputStream = new FileInputStream(configPath)) {
			allProps.load(inputStream);
		}

		allProps.put(StreamsConfig.APPLICATION_ID_CONFIG, allProps.getProperty("application.id"));
		allProps.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getPath());
		allProps.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);

		Topology topology = this.buildTopology(allProps, this.ticketSaleSerde(allProps));
		this.createTopics(allProps);

		final KafkaStreams streams = new KafkaStreams(topology, allProps);
		final CountDownLatch latch = new CountDownLatch(1);

		Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
			@Override
			public void run() {
				streams.close(Duration.ofSeconds(5));
				latch.countDown();
			}
		});

		try {
			streams.cleanUp();
			streams.start();
			latch.await();
		} catch (Throwable e) {
			System.exit(1);
		}
		System.exit(0);
	}

}
