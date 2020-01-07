package org.apache.nifi.accumulo.processors;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import datawave.ingest.data.config.DataTypeHelperImpl;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.nifi.accumulo.controllerservices.BaseAccumuloService;
import org.apache.nifi.accumulo.data.ContentRecordHandler;
import org.apache.nifi.accumulo.data.RecordIngestHelper;
import org.apache.nifi.annotation.behavior.DynamicProperties;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"hadoop", "accumulo", "put", "record"})
@DynamicProperties({
        @DynamicProperty(name = "visibility.<COLUMN FAMILY>", description = "Visibility label for everything under that column family " +
                "when a specific label for a particular column qualifier is not available.", expressionLanguageScope = ExpressionLanguageScope.FLOWFILE_ATTRIBUTES,
                value = "visibility label for <COLUMN FAMILY>"
        ),
        @DynamicProperty(name = "visibility.<COLUMN FAMILY>.<COLUMN QUALIFIER>", description = "Visibility label for the specified column qualifier " +
                "qualified by a configured column family.", expressionLanguageScope = ExpressionLanguageScope.FLOWFILE_ATTRIBUTES,
                value = "visibility label for <COLUMN FAMILY>:<COLUMN QUALIFIER>."
        )
})
public abstract class DatawaveAccumuloIngest extends BaseAccumuloProcessor {
    protected static final PropertyDescriptor DATA_NAME = new PropertyDescriptor.Builder()
            .name("Data name")
            .description("Data type name of the data")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor INGEST_HELPER = new PropertyDescriptor.Builder()
            .name("Ingest Helper")
            .description("Ingest Helper class")
            .required(false)
            .defaultValue(RecordIngestHelper.class.getCanonicalName())
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("Record Reader")
            .description("Hadoop Record reader class")
            .required(false)
            .defaultValue(DatawaveRecordReader.class.getCanonicalName())
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor DATA_HANDLER_CLASS = new PropertyDescriptor.Builder()
            .name("Handler Class")
            .description("Datawave handler class")
            .required(false)
            .defaultValue(ContentRecordHandler.class.getCanonicalName())
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor FATAL_ERRORS = new PropertyDescriptor.Builder()
            .name("Fatal Errors")
            .description("Comma separated list of errors considered fatal")
            .required(false)
            .defaultValue("MISSING_DATA_ERROR,INVALID_DATA_ERROR")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor UUID_FIELDS = new PropertyDescriptor.Builder()
            .name("UUID Fields")
            .description("Comma separated list of fields used for UUID calculation")
            .required(false)
            .defaultValue("UUID,PARENT_UUID")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor INDEXED_FIELDS = new PropertyDescriptor.Builder()
            .name("Indexed Fields")
            .description("Comma separated list of fields used for UUID calculation")
            .required(false).expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();


    protected static final PropertyDescriptor INDEX_ALL_FIELDS = new PropertyDescriptor.Builder()
            .name("Index All Fields")
            .description("True to index all fields")
            .required(false)
            .defaultValue("True").allowableValues("True","False")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    protected static final PropertyDescriptor INDEX_TABLE_NAME = new PropertyDescriptor.Builder()
            .name("Index Table Name")
            .description("Index table name")
            .required(true)
            .defaultValue("shardIndex")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor REVERSE_INDEX_TABLE_NAME = new PropertyDescriptor.Builder()
            .name("Reverse Index Table Name")
            .description("Reverse Index table name")
            .required(true)
            .defaultValue("shardReverseIndex")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor NUM_SHARD = new PropertyDescriptor.Builder()
            .name("Num shards")
            .description("Number of shards")
            .required(true)
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .build();


    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("A FlowFile is routed to this relationship after it has been successfully stored in Accumulo")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile is routed to this relationship if it cannot be sent to Accumulo")
            .build();

    protected final Multimap<String,Object> metadataForValidation = ArrayListMultimap.create(100, 1);

    // protected DataTypeHelperImpl dataTypeHelper;

    protected ConcurrentHashMap<String,DataTypeHelperImpl> datatypes = new ConcurrentHashMap<>();


    protected boolean requiredForValidation(String fieldName) {
        if (metadataForValidation.containsKey(fieldName)) {
            return false;
        }
        return false;
    }


    /**
     * Connector service which provides us a connector if the configuration is correct.
     */
    protected BaseAccumuloService accumuloConnectorService;

    /**
     * Connector that we need to persist while we are operational.
     */
    protected AccumuloClient client;

    /**
     * Table writer that will close when we shutdown or upon error.
     */
    protected MultiTableBatchWriter tableWriter = null;


    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>(baseProperties);
        properties.add(DATA_NAME);
        properties.add(INDEXED_FIELDS);
        properties.add(INDEX_ALL_FIELDS);
        properties.add(INDEX_TABLE_NAME);
        properties.add(REVERSE_INDEX_TABLE_NAME);
        properties.add(NUM_SHARD);
        properties.add(INGEST_HELPER);
        properties.add(RECORD_READER);
        properties.add(DATA_HANDLER_CLASS);
        properties.add(FATAL_ERRORS);
        properties.add(UUID_FIELDS);
        return properties;
    }


}
