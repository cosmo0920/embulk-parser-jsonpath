package org.embulk.parser.jsonpath;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.jayway.jsonpath.JsonPath;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInput;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.ParserPlugin;
import org.embulk.spi.Schema;
import org.embulk.spi.SchemaConfig;
import org.embulk.spi.json.JsonParser;
import org.embulk.spi.time.TimestampParser;
import org.embulk.spi.util.FileInputInputStream;
import org.embulk.spi.util.Timestamps;
import org.msgpack.value.Value;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;

import static java.util.Locale.ENGLISH;
import static org.msgpack.value.ValueFactory.newString;

public class JsonpathParserPlugin
        implements ParserPlugin
{

    private static final Logger logger = Exec.getLogger(JsonpathParserPlugin.class);

    private Map<String, Value> columnNameValues;

    public interface TypecastColumnOption
            extends Task
    {
        @Config("typecast")
        @ConfigDefault("null")
        public Optional<Boolean> getTypecast();
    }

    public interface PluginTask
            extends Task, TimestampParser.Task
    {
        @Config("root")
        public String getRoot();

        @Config("columns")
        SchemaConfig getSchemaConfig();

        @Config("default_typecast")
        @ConfigDefault("true")
        Boolean getDefaultTypecast();

        @Config("stop_on_invalid_record")
        @ConfigDefault("false")
        boolean getStopOnInvalidRecord();
    }

    @Override
    public void transaction(ConfigSource config, ParserPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        Schema schema = task.getSchemaConfig().toSchema();

        control.run(task.dump(), schema);
    }

    @Override
    public void run(TaskSource taskSource, Schema schema,
            FileInput input, PageOutput output)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);
        String json_root = task.getRoot();

        setColumnNameValues(schema);

        logger.debug("JSONPath = " + json_root);
        final TimestampParser[] timestampParsers = Timestamps.newTimestampColumnParsers(task, task.getSchemaConfig());
        final JsonParser json_parser = new JsonParser();
        final boolean stopOnInvalidRecord = task.getStopOnInvalidRecord();

        try (final PageBuilder pageBuilder = new PageBuilder(Exec.getBufferAllocator(), schema, output)) {
            ColumnVisitorImpl visitor = new ColumnVisitorImpl(task, schema, pageBuilder, timestampParsers);

            try (FileInputInputStream is = new FileInputInputStream(input)) {
                while (is.nextFile()) {
                    // TODO more efficient handling.
                    String json = JsonPath.read(is, json_root).toString();
                    Value value = json_parser.parse(json);
                    if (!value.isArrayValue()) {
                        throw new JsonRecordValidateException("Json string is not representing array value.");
                    }

                    for (Value record_value : value.asArrayValue()) {
                        if (!record_value.isMapValue()) {
                            if(stopOnInvalidRecord) {
                                throw new JsonRecordValidateException("Json string is not representing map value.");
                            }
                            logger.warn(String.format(ENGLISH,"Skipped invalid record  %s", record_value));
                            continue;
                        }

                        logger.debug("record_value = " + record_value.toString());
                        final Map<Value, Value> record = record_value.asMapValue().map();
                        for (Column column : schema.getColumns()) {
                            Value v = record.get(getColumnNameValue(column));
                            visitor.setValue(v);
                            column.visit(visitor);
                        }

                        pageBuilder.addRecord();
                    }
                }
            }
            catch (IOException e) {
                // TODO more efficient exception handling.
                throw new DataException("catch IOException " + e);
            }

            pageBuilder.finish();
        }
    }

    private void setColumnNameValues(Schema schema)
    {
        ImmutableMap.Builder<String, Value> builder = ImmutableMap.builder();
        for (Column column : schema.getColumns()) {
            String name = column.getName();
            builder.put(name, newString(name));
        }
        columnNameValues = builder.build();
    }

    private Value getColumnNameValue(Column column)
    {
        return columnNameValues.get(column.getName());
    }
}
