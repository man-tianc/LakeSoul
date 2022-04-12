/*
 *
 *  * Copyright [2022] [DMetaSoul Team]
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.flink.lakesoul;


import org.apache.flink.api.common.serialization.BulkWriter;
import org.apache.flink.api.common.serialization.Encoder;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.core.fs.FSDataOutputStream;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.lakesoul.tools.FlinkUtil;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.OutputFileConfig;
import org.apache.flink.streaming.api.functions.sink.filesystem.PartFileInfo;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.SimpleVersionedStringSerializer;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.CheckpointRollingPolicy;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.connector.sink.DataStreamSinkProvider;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkFunctionProvider;
import org.apache.flink.table.connector.sink.abilities.SupportsOverwrite;
import org.apache.flink.table.connector.sink.abilities.SupportsPartitioning;
import org.apache.flink.table.data.BoxedWrapperRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.table.filesystem.FileSystemFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;
import org.apache.flink.types.RowKind;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.flink.table.filesystem.RowDataPartitionComputer;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.flink.table.filesystem.PartitionComputer;
import org.apache.flink.util.Preconditions;

import static org.apache.flink.table.types.logical.utils.LogicalTypeChecks.isCompositeType;

public class LakesoulTableSink implements DynamicTableSink, SupportsPartitioning, SupportsOverwrite {
    private boolean overwrite = false;
    private ObjectIdentifier tableIdentifier;
    private Configuration tableOptions;
    private DataType physicalRowDataType;
    private Path path;
    private String defaultPartName;

    List<String> partitionKeys;

    private  EncodingFormat<BulkWriter.Factory<RowData>> bulkWriterFormat;
    private  EncodingFormat<SerializationSchema<RowData>> serializationFormat;


    @Override
    public boolean requiresPartitionGrouping(boolean supportsGrouping) {
        return false;
    }

    public LakesoulTableSink(  ObjectIdentifier tableIdentifier,
                               DataType physicalRowDataType,
                               List<String> partitionKeys,
                               ReadableConfig tableOptions,
                               EncodingFormat<BulkWriter.Factory<RowData>> bulkWriterFormat,
                              EncodingFormat<SerializationSchema<RowData>> serializationFormat){
        this.bulkWriterFormat = bulkWriterFormat;
        this.serializationFormat = serializationFormat;
        this.tableIdentifier = tableIdentifier;
        this.partitionKeys=partitionKeys;
        this.tableOptions = (Configuration) tableOptions;
        this.physicalRowDataType = physicalRowDataType;
    }
    private LakesoulTableSink(LakesoulTableSink lts){
        this.overwrite=lts.overwrite;
    }
    @Override
    public ChangelogMode getChangelogMode(ChangelogMode changelogMode)
    {
        ChangelogMode.Builder builder = ChangelogMode.newBuilder();
        for (RowKind kind : changelogMode.getContainedKinds()) {
            builder.addContainedKind(kind);
        }
        return builder.build();
    }

    @Override
    public SinkRuntimeProvider getSinkRuntimeProvider(Context sinkContext) {
//        if (sinkContext.isBounded()) {
//            return SinkFunctionProvider.of(new BatchLakesoulSink());
//        } else {
//            if (overwrite) {
//                throw new IllegalStateException("Streaming mode not support overwrite.");
//            }
//            return SinkFunctionProvider.of(new StreamLakesoulSink());
 //       }
        return (DataStreamSinkProvider)(dataStream) -> consume( dataStream, sinkContext);
    }
    private DataStreamSink<?> consume(DataStream<RowData> dataStream, Context sinkContext) {
        final int parallelism = dataStream.getParallelism();
        return createStreamingSink(dataStream, sinkContext, parallelism);

//        if (sinkContext.isBounded()) {
//            return createBatchSink(dataStream, sinkContext, parallelism);
//        } else {
//            if (overwrite) {
//                throw new IllegalStateException("Streaming mode not support overwrite.");
//            }
//            //return SinkFunctionProvider.of(new RowDataPrintFunction());
//            return createStreamingSink(dataStream, sinkContext, parallelism);
//        }

    }
    private DataStreamSink<?> createBatchSink(
            DataStream<RowData> inputStream, Context sinkContext, final int parallelism) {
        return null;
    }

    private DataStreamSink<?> createStreamingSink(
            DataStream<RowData> dataStream,
            Context sinkContext,
            final int parallelism) {
        FileSystemFactory fsFactory = FileSystem::get;
        RowDataPartitionComputer computer = partitionComputer();
        Object writer = createWriter(sinkContext);
        boolean isEncoder = writer instanceof Encoder;
        LakesoulTableBucketAssigner assigner = new LakesoulTableBucketAssigner(computer);
        LakesoulRollingPolicy lakesoulPolicy=new LakesoulRollingPolicy(!isEncoder);
        String randomPrefix = "part-" + UUID.randomUUID().toString();
        OutputFileConfig.OutputFileConfigBuilder fileNamingBuilder = OutputFileConfig.builder();
        fileNamingBuilder = fileNamingBuilder.withPartPrefix(randomPrefix);
        OutputFileConfig fileNamingConfig = fileNamingBuilder.build();
        this.path = new Path(tableOptions.getString( CatalogProperties.PATH));
        LakesoulFileSink.BucketsBuilder<RowData, String, ? extends LakesoulFileSink.BucketsBuilder<RowData, ?, ?>> bucketsBuilder;
        if (isEncoder) {
            //noinspection unchecked
            bucketsBuilder =
                    LakesoulFileSink.forRowFormat(
                            path,
                            new ProjectionEncoder((Encoder<RowData>) writer, computer))
                            .withBucketAssigner(assigner)
                            .withOutputFileConfig(fileNamingConfig)
                            .withRollingPolicy(lakesoulPolicy);
        } else {
            //noinspection unchecked
            bucketsBuilder =
                    LakesoulFileSink.forBulkFormat(
                            path,
                            new ProjectionBulkFactory(
                                    (BulkWriter.Factory<RowData>) writer, computer))
                            .withBucketAssigner(assigner)
                            .withOutputFileConfig(fileNamingConfig)
                            .withRollingPolicy(lakesoulPolicy);
        }
        long bucketCheckInterval = Duration.ofMinutes(1).toMillis();

        DataStream<DataInfo> writerStream;
        writerStream =
                 LakesoulSink.writer(
                        bucketCheckInterval,
                        dataStream,
                        bucketsBuilder,
                        fileNamingConfig,
                        parallelism,
                        partitionKeys,
                        tableOptions);
        return LakesoulSink.sink(
                writerStream,
                path,
                tableIdentifier,
                partitionKeys,
                fsFactory,
                tableOptions);

    }
    private RowDataPartitionComputer partitionComputer() {
        return new RowDataPartitionComputer(
                defaultPartName,
                getFieldNames(physicalRowDataType).toArray(new String[0]),
                getFieldDataTypes(physicalRowDataType).toArray(new DataType[0]),
                partitionKeys.toArray(new String[0]));
    }
    @Override
    public DynamicTableSink copy() {
        return new LakesoulTableSink(this);
    }

    @Override
    public String asSummaryString() {
        return "lakesoul table sink";
    }

    @Override
    public void applyOverwrite(boolean newOverwrite) {
        this.overwrite = newOverwrite;
    }

    @Override
    public void applyStaticPartition(Map<String, String> map) {

    }


    private Object createWriter(Context sinkContext) {

        DataType physicalDataTypeWithoutPartitionColumns = getFields(physicalRowDataType).stream()
                        .filter(field -> !partitionKeys.contains(field.getName()))
                        .collect(Collectors.collectingAndThen(Collectors.toList(), LakesoulTableSink::ROW));

        if (bulkWriterFormat != null) {
            return bulkWriterFormat.createRuntimeEncoder(
                    sinkContext, physicalDataTypeWithoutPartitionColumns);
        } else if (serializationFormat != null) {
            return new LakesoulSchemaAdapter(
                    serializationFormat.createRuntimeEncoder(
                            sinkContext, physicalDataTypeWithoutPartitionColumns));
        } else {
            throw new TableException("Can not find format factory.");
        }
    }


    public static DataType ROW(List<DataTypes.Field> fields) {
        return DataTypes.ROW(fields.toArray(new DataTypes.Field[0]));
    }

    public static List<String> getFieldNames(DataType dataType) {
        final LogicalType type = dataType.getLogicalType();
        if (type.getTypeRoot() == LogicalTypeRoot.DISTINCT_TYPE  ) {
            return getFieldNames(dataType.getChildren().get(0));
        } else if (isCompositeType(type)) {
            return LogicalTypeChecks.getFieldNames(type);
        }
        return Collections.emptyList();
    }

    /**
     * Returns the first-level field data types for the provided {@link DataType}.
     *
     * <p>Note: This method returns an empty list for every {@link DataType} that is not a composite
     * type.
     */
    public static List<DataType> getFieldDataTypes(DataType dataType) {
        final LogicalType type = dataType.getLogicalType();
        if (type.getTypeRoot() == LogicalTypeRoot.DISTINCT_TYPE  ) {
            return getFieldDataTypes(dataType.getChildren().get(0));
        } else if (isCompositeType(type)) {
            return dataType.getChildren();
        }
        return Collections.emptyList();
    }

    /**
     * Returns the count of the first-level fields for the provided {@link DataType}.
     *
     * <p>Note: This method returns {@code 0} for every {@link DataType} that is not a composite
     * type.
     */
    public static int getFieldCount(DataType dataType) {
        return getFieldDataTypes(dataType).size();
    }

    /**
     * Returns an ordered list of fields starting from the provided {@link DataType}.
     *
     * <p>Note: This method returns an empty list for every {@link DataType} that is not a composite
     * type.
     */
    public static List<DataTypes.Field> getFields(DataType dataType) {
        final List<String> names = getFieldNames(dataType);
        final List<DataType> dataTypes = getFieldDataTypes(dataType);
        return IntStream.range(0, names.size())
                .mapToObj(i -> DataTypes.FIELD(names.get(i), dataTypes.get(i)))
                .collect(Collectors.toList());
    }


    public static class LakesoulRollingPolicy extends CheckpointRollingPolicy<RowData, String> {

        private  boolean rollOnCheckpoint;

        public LakesoulRollingPolicy(
                boolean rollOnCheckpoint
        ) {
            this.rollOnCheckpoint = rollOnCheckpoint;
        }

        @Override
        public boolean shouldRollOnCheckpoint(PartFileInfo<String> partFileState) {
            boolean rollOnCheckpoint = this.rollOnCheckpoint;
            return rollOnCheckpoint;
        }

        @Override
        public boolean shouldRollOnEvent(PartFileInfo<String> partFileState, RowData element)
                throws IOException {
            return false;
        }

        @Override
        public boolean shouldRollOnProcessingTime(
                PartFileInfo<String> partFileState, long currentTime) {
            return false;
        }
    }




    public static class LakesoulTableBucketAssigner implements BucketAssigner<RowData, String> {

        private final PartitionComputer<RowData> computer;

        public LakesoulTableBucketAssigner(PartitionComputer<RowData> computer) {
            this.computer = computer;
        }

        @Override
        public String getBucketId(RowData element, Context context) {
            try {
                return FlinkUtil.generatePartitionPath(
                        computer.generatePartValues(element));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public SimpleVersionedSerializer<String> getSerializer() {
            return SimpleVersionedStringSerializer.INSTANCE;
        }
    }

    private static class ProjectionEncoder implements Encoder<RowData> {

        private final Encoder<RowData> encoder;
        private final RowDataPartitionComputer computer;

        private ProjectionEncoder(Encoder<RowData> encoder, RowDataPartitionComputer computer) {
            this.encoder = encoder;
            this.computer = computer;
        }

        @Override
        public void encode(RowData element, OutputStream stream) throws IOException {
            encoder.encode(computer.projectColumnsToWrite(element), stream);
        }
    }


    public static class ProjectionBulkFactory implements BulkWriter.Factory<RowData> {

        private final BulkWriter.Factory<RowData> factory;
        private final RowDataPartitionComputer computer;

        public ProjectionBulkFactory(
                BulkWriter.Factory<RowData> factory, RowDataPartitionComputer computer) {
            this.factory = factory;
            this.computer = computer;
        }

        @Override
        public BulkWriter<RowData> create(FSDataOutputStream out) throws IOException {
            BulkWriter<RowData> writer = factory.create(out);
            return new BulkWriter<RowData>() {

                @Override
                public void addElement(RowData element) throws IOException {
                    writer.addElement(computer.projectColumnsToWrite(element));
                }

                @Override
                public void flush() throws IOException {
                    writer.flush();
                }

                @Override
                public void finish() throws IOException {
                    writer.finish();
                }
            };
        }
    }

    public static class TableRollingPolicy extends CheckpointRollingPolicy<RowData, String> {

        private final boolean rollOnCheckpoint;
        private final long rollingFileSize;

        public TableRollingPolicy(
                boolean rollOnCheckpoint,
                long rollingFileSize) {
            this.rollOnCheckpoint = rollOnCheckpoint;
            Preconditions.checkArgument(rollingFileSize > 0L);
            this.rollingFileSize = rollingFileSize;
        }
        @Override
        public boolean shouldRollOnCheckpoint(PartFileInfo<String> partFileState) {
            try {
                return rollOnCheckpoint || partFileState.getSize() > rollingFileSize;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        @Override
        public boolean shouldRollOnEvent(PartFileInfo<String> partFileState, RowData element)
                throws IOException {
            return partFileState.getSize() > rollingFileSize;
        }

        @Override
        public boolean shouldRollOnProcessingTime(
                PartFileInfo<String> partFileState, long currentTime) {
            return false;
        }
    }


    private static class BatchLakesoulSink extends RichSinkFunction<RowData> implements CheckpointedFunction {

        private static final long serialVersionUID = 1L;
       // private
        private BatchLakesoulSink() {
        }

        @Override
        public void invoke(RowData value, Context context){
            BoxedWrapperRowData grdata=(BoxedWrapperRowData)value;
            Long val=grdata.getLong(0);
            try {
                FileOutputStream fos = new FileOutputStream("lakesoultest.txt",true);
                OutputStreamWriter osw=new OutputStreamWriter(fos);
                osw.write(String.valueOf(val));
                osw.write("\n");
                osw.flush();
                osw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {

        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {

        }
        @Override
        public void open(Configuration parameters){

        }
        @Override
        public void close(){

        }

    }
}


