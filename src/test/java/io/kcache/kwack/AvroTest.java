package io.kcache.kwack;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.nio.ByteBuffer;
import java.util.Properties;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AvroTest extends AbstractSchemaTest {

    private Schema createEnumSchema() {
        String enumSchema = "{\"name\": \"Kind\",\"namespace\": \"example.avro\",\n"
            + "   \"type\": \"enum\",\n"
            + "  \"symbols\" : [\"ONE\", \"TWO\", \"THREE\"]\n"
            + "}";
        Schema.Parser parser = new Schema.Parser();
        Schema schema = parser.parse(enumSchema);
        return schema;
    }

    private Schema createFixedSchema() {
        String fixedSchema = "{\"name\": \"Fixed\",\n"
            + "   \"type\": \"fixed\",\n"
            + "  \"size\" : 4\n"
            + "}";
        Schema.Parser parser = new Schema.Parser();
        Schema schema = parser.parse(fixedSchema);
        return schema;
    }

    private Schema createComplexSchema() {
        return new Schema.Parser().parse(
            "{\"namespace\": \"namespace\",\n"
                + " \"type\": \"record\",\n"
                + " \"name\": \"test\",\n"
                + " \"fields\": [\n"
                + "     {\"name\": \"null\", \"type\": \"null\"},\n"
                + "     {\"name\": \"boolean\", \"type\": \"boolean\"},\n"
                + "     {\"name\": \"int\", \"type\": \"int\"},\n"
                + "     {\"name\": \"long\", \"type\": \"long\"},\n"
                + "     {\"name\": \"float\", \"type\": \"float\"},\n"
                + "     {\"name\": \"double\", \"type\": \"double\"},\n"
                + "     {\"name\": \"bytes\", \"type\": \"bytes\"},\n"
                + "     {\"name\": \"string\", \"type\": \"string\", \"aliases\": [\"string_alias\"]},\n"
                + "     {\"name\": \"enum\",\n"
                + "       \"type\": {\n"
                + "         \"name\": \"Kind\",\n"
                + "         \"type\": \"enum\",\n"
                + "         \"symbols\" : [\"ONE\", \"TWO\", \"THREE\"]\n"
                + "       }\n"
                + "     },\n"
                + "     {\"name\": \"array\",\n"
                + "       \"type\": {\n"
                + "         \"type\": \"array\",\n"
                + "         \"items\" : \"string\"\n"
                + "       }\n"
                + "     },\n"
                + "     {\"name\": \"map\",\n"
                + "       \"type\": {\n"
                + "         \"type\": \"map\",\n"
                + "         \"values\" : \"string\"\n"
                + "       }\n"
                + "     },\n"
                + "     {\"name\": \"union\", \"type\": [\"null\", \"string\"]},\n"
                + "     {\"name\": \"fixed\",\n"
                + "       \"type\": {\n"
                + "         \"name\": \"Fixed\",\n"
                + "         \"type\": \"fixed\",\n"
                + "         \"size\" : 4\n"
                + "       }\n"
                + "     }\n"
                + "]\n"
                + "}");
    }

    private IndexedRecord createComplexRecord() {
        Schema enumSchema = createEnumSchema();
        Schema fixedSchema = createFixedSchema();
        Schema schema = createComplexSchema();
        GenericRecord avroRecord = new GenericData.Record(schema);
        avroRecord.put("null", null);
        avroRecord.put("boolean", true);
        avroRecord.put("int", 1);
        avroRecord.put("long", 2L);
        avroRecord.put("float", 3.0f);
        avroRecord.put("double", 4.0d);
        avroRecord.put("bytes", ByteBuffer.wrap(new byte[]{0, 1, 2}));
        avroRecord.put("string", "testUser");
        avroRecord.put("enum", new GenericData.EnumSymbol(enumSchema, "ONE"));
        avroRecord.put("array", ImmutableList.of("hi", "there"));
        avroRecord.put("map", ImmutableMap.of("bye", "there"));
        avroRecord.put("union", "zap");
        avroRecord.put("fixed", new GenericData.Fixed(fixedSchema, new byte[]{0, 0, 0, 0}));
        return avroRecord;
    }

    @Test
    public void testComplex() {
        String topic = "test-avro";
        IndexedRecord record = createComplexRecord();
        Properties producerProps = createProducerProps(MOCK_URL);
        KafkaProducer producer = createProducer(producerProps);
        produce(producer, topic, new Object[] { record });
    }

    @Override
    protected Class<?> getValueSerializer() {
        return io.confluent.kafka.serializers.KafkaAvroSerializer.class;
    }
}
