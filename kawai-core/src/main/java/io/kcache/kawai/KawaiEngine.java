/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kcache.kawai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Message;
import io.kcache.CacheUpdateHandler;
import io.kcache.KafkaCache;
import io.kcache.KafkaCacheConfig;
import io.kcache.caffeine.CaffeineCache;
import io.kcache.kawai.KawaiConfig.SerdeType;
import io.kcache.kawai.schema.RelDef;
import io.kcache.kawai.translator.Context;
import io.kcache.kawai.translator.Translator;
import io.kcache.kawai.translator.avro.AvroTranslator;
import io.kcache.kawai.util.Jackson;
import io.vavr.Tuple2;
import io.vavr.control.Either;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.common.serialization.BytesSerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.DoubleDeserializer;
import org.apache.kafka.common.serialization.DoubleSerializer;
import org.apache.kafka.common.serialization.FloatDeserializer;
import org.apache.kafka.common.serialization.FloatSerializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.ShortDeserializer;
import org.apache.kafka.common.serialization.ShortSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.Utils;
import org.checkerframework.checker.units.qual.C;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.SchemaProvider;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaUtils;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import io.confluent.kafka.schemaregistry.json.JsonSchemaUtils;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchemaProvider;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchemaUtils;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;

public class KawaiEngine implements Configurable, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(KawaiEngine.class);

    public static final String REGISTERED_SCHEMAS_COLLECTION_NAME = "_registered_schemas";
    public static final String STAGED_SCHEMAS_COLLECTION_NAME = "_staged_schemas";

    private static final ObjectMapper MAPPER = Jackson.newObjectMapper();

    private KawaiConfig config;
    private DuckDBConnection conn;
    private SchemaRegistryClient schemaRegistry;
    private Map<String, SchemaProvider> schemaProviders;
    private Map<String, KawaiConfig.Serde> keySerdes;
    private Map<String, KawaiConfig.Serde> valueSerdes;
    private final Map<String, Either<SerdeType, ParsedSchema>> keySchemas = new HashMap<>();
    private final Map<String, Either<SerdeType, ParsedSchema>> valueSchemas = new HashMap<>();
    private final Map<String, KafkaCache<Bytes, Bytes>> caches;
    private final AtomicBoolean initialized;

    private int idCounter = 0;

    private static KawaiEngine INSTANCE;

    public synchronized static KawaiEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new KawaiEngine();
        }
        return INSTANCE;
    }

    public synchronized static void closeInstance() {
        if (INSTANCE != null) {
            try {
                INSTANCE.close();
            } catch (IOException e) {
                LOG.warn("Could not close engine", e);
            }
            INSTANCE = null;
        }
    }

    private KawaiEngine() {
        caches = new HashMap<>();
        initialized = new AtomicBoolean();
    }

    public void configure(Map<String, ?> configs) {
        configure(new KawaiConfig(configs));
    }

    public void configure(KawaiConfig config) {
        this.config = config;
    }

    public void init() {
        try {
            conn = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");

            List<SchemaProvider> providers = Arrays.asList(
                new AvroSchemaProvider(), new JsonSchemaProvider(), new ProtobufSchemaProvider()
            );
            schemaRegistry = createSchemaRegistry(
                config.getSchemaRegistryUrls(), providers, config.originals());
            schemaProviders = providers.stream()
                .collect(Collectors.toMap(SchemaProvider::schemaType, p -> p));

            keySerdes = config.getKeySerdes();
            valueSerdes = config.getValueSerdes();

            initCaches(conn);

            boolean isInitialized = initialized.compareAndSet(false, true);
            if (!isInitialized) {
                throw new IllegalStateException("Illegal state while initializing engine. Engine "
                    + "was already initialized");
            }
        } catch (SQLException e) {
            LOG.error("Could not initialize engine", e);
            throw new RuntimeException(e);
        }
    }

    public static SchemaRegistryClient createSchemaRegistry(
        List<String> urls, List<SchemaProvider> providers, Map<String, Object> configs) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        String mockScope = MockSchemaRegistry.validateAndMaybeGetMockScope(urls);
        if (mockScope != null) {
            return MockSchemaRegistry.getClientForScope(mockScope, providers);
        } else {
            return new CachedSchemaRegistryClient(urls, 1000, providers, configs);
        }
    }

    public static void resetSchemaRegistry(List<String> urls, SchemaRegistryClient schemaRegistry) {
        if (urls != null && !urls.isEmpty()) {
            String mockScope = MockSchemaRegistry.validateAndMaybeGetMockScope(urls);
            if (mockScope != null) {
                MockSchemaRegistry.dropScope(mockScope);
            } else {
                schemaRegistry.reset();
            }
        }
    }

    public SchemaRegistryClient getSchemaRegistry() {
        if (schemaRegistry == null) {
            throw new ConfigException("Missing schema registry URL");
        }
        return schemaRegistry;
    }

    public SchemaProvider getSchemaProvider(String schemaType) {
        return schemaProviders.get(schemaType);
    }

    public int nextId() {
        return --idCounter;
    }

    public Either<SerdeType, ParsedSchema> getKeySchema(String topic) {
        return keySchemas.computeIfAbsent(topic, t -> getSchema(topic + "-key",
            keySerdes.getOrDefault(topic, KawaiConfig.Serde.KEY_DEFAULT)));
    }

    public Either<SerdeType, ParsedSchema> getValueSchema(String topic) {
        return valueSchemas.computeIfAbsent(topic, t -> getSchema(topic + "-value",
            valueSerdes.getOrDefault(topic, KawaiConfig.Serde.VALUE_DEFAULT)));
    }

    private Either<SerdeType, ParsedSchema> getSchema(String subject, KawaiConfig.Serde serde) {
        SerdeType serdeType = serde.getSerdeType();
        switch (serdeType) {
            case SHORT:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case STRING:
            case BINARY:
                return Either.left(serdeType);
            case AVRO:
            case JSON:
            case PROTO:
                return parseSchema(serde)
                    .<Either<SerdeType, ParsedSchema>>map(Either::right)
                    .orElseGet(() -> Either.left(SerdeType.BINARY));
            case LATEST:
                return getLatestSchema(subject).<Either<SerdeType, ParsedSchema>>map(Either::right)
                    .orElseGet(() -> Either.left(SerdeType.BINARY));
            case ID:
                return getSchemaById(serde.getId()).<Either<SerdeType, ParsedSchema>>map(Either::right)
                    .orElseGet(() -> Either.left(SerdeType.BINARY));
            default:
                throw new IllegalArgumentException("Illegal serde type: " + serde.getSerdeType());
        }
    }

    private Optional<ParsedSchema> parseSchema(KawaiConfig.Serde serde) {
        return parseSchema(serde.getSchemaType(), serde.getSchema(), serde.getSchemaReferences());
    }

    public Optional<ParsedSchema> parseSchema(String schemaType, String schema,
        List<SchemaReference> references) {
        try {
            Schema s = new Schema(null, null, null, schemaType, references, schema);
            ParsedSchema parsedSchema =
                getSchemaProvider(schemaType).parseSchemaOrElseThrow(s, false, false);
            parsedSchema.validate(false);
            return Optional.of(parsedSchema);
        } catch (Exception e) {
            LOG.error("Could not parse schema " + schema, e);
            return Optional.empty();
        }
    }

    public Optional<ParsedSchema> getLatestSchema(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        try {
            SchemaMetadata schema = getSchemaRegistry().getLatestSchemaMetadata(subject);
            Optional<ParsedSchema> optSchema =
                getSchemaRegistry().parseSchema(new Schema(null, schema));
            return optSchema;
        } catch (Exception e) {
            LOG.error("Could not find latest schema for subject " + subject, e);
            return Optional.empty();
        }
    }

    public Optional<ParsedSchema> getSchemaById(int id) {
        try {
            ParsedSchema schema = getSchemaRegistry().getSchemaById(id);
            return Optional.of(schema);
        } catch (Exception e) {
            LOG.error("Could not find schema with id " + id, e);
            return Optional.empty();
        }
    }

    public Object deserializeKey(String topic, byte[] bytes) throws IOException {
        return deserialize(true, topic, bytes);
    }

    public Object deserializeValue(String topic, byte[] bytes) throws IOException {
        return deserialize(false, topic, bytes);
    }

    private Object deserialize(boolean isKey, String topic, byte[] bytes) throws IOException {
        Either<SerdeType, ParsedSchema> schema =
            isKey ? getKeySchema(topic) : getValueSchema(topic);

        Deserializer<?> deserializer = getDeserializer(schema);

        Object object = deserializer.deserialize(topic, bytes);
        if (schema.isRight()) {
            ParsedSchema parsedSchema = schema.get();
            Context ctx = new Context(isKey);
            Translator translator = null;
            switch (parsedSchema.schemaType()) {
                case "AVRO":
                    translator = new AvroTranslator();
                    break;
                case "JSON":
                    break;
                case "PROTOBUF":
                    break;
                default:
                    throw new IllegalArgumentException("Illegal type " + parsedSchema.schemaType());
            }
            RelDef relDef = translator.schemaToRelDef(ctx, parsedSchema);
            object = translator.messageToRow(ctx, parsedSchema, object, relDef);
        }

        return object;
    }

    public byte[] serializeKey(String topic, Object object) throws IOException {
        return serialize(true, topic, object);
    }

    public byte[] serializeValue(String topic, Object object) throws IOException {
        return serialize(false, topic, object);
    }

    @SuppressWarnings("unchecked")
    private byte[] serialize(boolean isKey, String topic, Object object) throws IOException {
        Either<SerdeType, ParsedSchema> schema =
            isKey ? getKeySchema(topic) : getValueSchema(topic);

        Serializer<Object> serializer = (Serializer<Object>) getSerializer(schema);

        if (schema.isRight()) {
            ParsedSchema parsedSchema = schema.get();
            Context ctx = new Context(isKey);
            Translator translator = null;
            switch (parsedSchema.schemaType()) {
                case "AVRO":
                    translator = new AvroTranslator();
                    break;
                case "JSON":
                    break;
                case "PROTOBUF":
                    break;
                default:
                    throw new IllegalArgumentException("Illegal type " + parsedSchema.schemaType());
            }
            // TODO
        }

        return serializer.serialize(topic, object);
    }

    public Serializer<?> getSerializer(Either<SerdeType, ParsedSchema> schema) {
        if (schema.isRight()) {
            ParsedSchema parsedSchema = schema.get();
            switch (parsedSchema.schemaType()) {
                case "AVRO":
                    return new KafkaAvroSerializer(getSchemaRegistry(), config.originals());
                case "JSON":
                    return new KafkaJsonSchemaSerializer<>(getSchemaRegistry(), config.originals());
                case "PROTOBUF":
                    return new KafkaProtobufSerializer<>(getSchemaRegistry(), config.originals());
                default:
                    throw new IllegalArgumentException("Illegal type " + parsedSchema.schemaType());
            }
        } else {
            switch (schema.getLeft()) {
                case STRING:
                    return new StringSerializer();
                case SHORT:
                    return new ShortSerializer();
                case INT:
                    return new IntegerSerializer();
                case LONG:
                    return new LongSerializer();
                case FLOAT:
                    return new FloatSerializer();
                case DOUBLE:
                    return new DoubleSerializer();
                case BINARY:
                    return new BytesSerializer();
                default:
                    throw new IllegalArgumentException("Illegal type " + schema.getLeft());
            }
        }
    }

    public Deserializer<?> getDeserializer(Either<SerdeType, ParsedSchema> schema) {
        if (schema.isRight()) {
            ParsedSchema parsedSchema = schema.get();
            switch (parsedSchema.schemaType()) {
                case "AVRO":
                    return new KafkaAvroDeserializer(getSchemaRegistry(), config.originals());
                case "JSON":
                    return new KafkaJsonSchemaDeserializer<>(getSchemaRegistry(), config.originals());
                case "PROTOBUF":
                    return new KafkaProtobufDeserializer<>(getSchemaRegistry(), config.originals());
                default:
                    throw new IllegalArgumentException("Illegal type " + parsedSchema.schemaType());
            }
        } else {
            switch (schema.getLeft()) {
                case STRING:
                    return new StringDeserializer();
                case SHORT:
                    return new ShortDeserializer();
                case INT:
                    return new IntegerDeserializer();
                case LONG:
                    return new LongDeserializer();
                case FLOAT:
                    return new FloatDeserializer();
                case DOUBLE:
                    return new DoubleDeserializer();
                case BINARY:
                    return new BytesDeserializer();
                default:
                    throw new IllegalArgumentException("Illegal type " + schema.getLeft());
            }
        }
    }

    private void initCaches(DuckDBConnection conn) {
        for (String topic : config.getTopics()) {
            initCache(conn, topic);
        }
    }

    private void initCache(DuckDBConnection conn, String topic) {
        Map<String, Object> originals = config.originals();
        Map<String, Object> configs = new HashMap<>(originals);
        for (Map.Entry<String, Object> config : originals.entrySet()) {
            if (!config.getKey().startsWith("kafkacache.")) {
                configs.put("kafkacache." + config.getKey(), config.getValue());
            }
        }
        String groupId = (String)
            configs.getOrDefault(KafkaCacheConfig.KAFKACACHE_GROUP_ID_CONFIG, "kawai-1");
        configs.put(KafkaCacheConfig.KAFKACACHE_TOPIC_CONFIG, topic);
        configs.put(KafkaCacheConfig.KAFKACACHE_GROUP_ID_CONFIG, groupId);
        configs.put(KafkaCacheConfig.KAFKACACHE_CLIENT_ID_CONFIG, groupId + "-" + topic);
        configs.put(KafkaCacheConfig.KAFKACACHE_TOPIC_SKIP_VALIDATION_CONFIG, true);
        KafkaCache<Bytes, Bytes> cache = new KafkaCache<>(
            new KafkaCacheConfig(configs),
            Serdes.Bytes(),
            Serdes.Bytes(),
            new UpdateHandler(conn),
            new CaffeineCache<>(100, Duration.ofMillis(10000), null)
        );
        cache.init();
        caches.put(topic, cache);
    }

    class UpdateHandler implements CacheUpdateHandler<Bytes, Bytes> {
        private DuckDBConnection conn;

        public UpdateHandler(DuckDBConnection conn) {
            this.conn = conn;
        }

        public void handleUpdate(Headers headers,
                                 Bytes key, Bytes value, Bytes oldValue,
                                 TopicPartition tp, long offset, long ts, TimestampType tsType,
                                 Optional<Integer> leaderEpoch) {
            String topic = tp.topic();
            int partition = tp.partition();
            Map<String, List<byte[]>> headersObj = convertHeaders(headers);
            Integer keySchemaId = null;
            Integer valueSchemaId = null;
            Object keyObj = null;
            Object valueObj = null;

            try {
                if (key != null && key.get() != Bytes.EMPTY) {
                    if (getKeySchema(topic).isRight()) {
                        keySchemaId = schemaIdFor(key.get());
                    }
                    keyObj = deserializeKey(topic, key.get());
                }

                if (getValueSchema(topic).isRight()) {
                    valueSchemaId = schemaIdFor(value.get());
                }
                valueObj = deserializeValue(topic, value.get());

                int index = 1;
                try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + topic
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setObject(index++, keyObj);
                    if (valueObj instanceof Object[]) {
                        Object[] values = (Object[]) valueObj;
                        for (Object v : values) {
                            stmt.setObject(index++, v);
                        }
                    } else {
                        stmt.setObject(index++, valueObj);
                    }
                    //stmt.setObject(index++, metaObj);

                    stmt.execute();
                }
            } catch (IOException | SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public void handleUpdate(Bytes key, Bytes value, Bytes oldValue,
                                 TopicPartition tp, long offset, long ts) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        private Map<String, List<byte[]>> convertHeaders(Headers headers) {
            if (headers == null) {
                return null;
            }
            Map<String, List<byte[]>> map = new HashMap<>();
            for (Header header : headers) {
                List<byte[]> values = new ArrayList<>();
                values.add(header.value());
                map.merge(header.key(), values, (oldV, v) -> {
                    oldV.addAll(v);
                    return oldV;
                });
            }
            return map;
        }

        private static final int MAGIC_BYTE = 0x0;

        private int schemaIdFor(byte[] payload) {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            if (buffer.get() != MAGIC_BYTE) {
                throw new UncheckedIOException(new IOException("Unknown magic byte!"));
            }
            return buffer.getInt();
        }

        private String trace(Throwable t) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            t.printStackTrace(new PrintStream(output, false, StandardCharsets.UTF_8));
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public void sync() {
        caches.forEach((key, value) -> {
            try {
                value.sync();
            } catch (Exception e) {
                LOG.warn("Could not sync cache for " + key);
            }
        });
    }

    public KafkaCache<Bytes, Bytes> getCache(String topic) {
        return caches.get(topic);
    }

    @Override
    public void close() throws IOException {
        caches.forEach((key, value) -> {
            try {
                value.close();
            } catch (IOException e) {
                LOG.warn("Could not close cache for " + key);
            }
        });
        resetSchemaRegistry(config.getSchemaRegistryUrls(), schemaRegistry);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getConfiguredInstance(String className, Map<String, ?> configs) {
        try {
            Class<T> cls = (Class<T>) Class.forName(className);
            Object o = Utils.newInstance(cls);
            if (o instanceof Configurable) {
                ((Configurable) o).configure(configs);
            }
            return cls.cast(o);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
