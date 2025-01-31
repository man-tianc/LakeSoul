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

import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.functions.sink.filesystem.*;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

public abstract class LakesoulAbstractStreamingWriter <IN, OUT> extends AbstractStreamOperator<OUT>
        implements OneInputStreamOperator<IN, OUT>, BoundedOneInput {

    private static final long serialVersionUID = 1L;

    // ------------------------ configuration fields --------------------------

    private  long bucketCheckInterval;

    private  LakesoulFileSink.BucketsBuilder<
            IN, String, ? extends LakesoulFileSink.BucketsBuilder<IN, String, ?>>
            bucketsBuilder;

    // --------------------------- runtime fields -----------------------------

    protected transient Buckets<IN, String> buckets;

    private transient StreamingFileSinkHelper<IN> helper;

    protected transient long currentWatermark;

    public LakesoulAbstractStreamingWriter(
            long bucketCheckInterval,
            LakesoulFileSink.BucketsBuilder<
                    IN, String, ? extends LakesoulFileSink.BucketsBuilder<IN, String, ?>>
                    bucketsBuilder) {
        this.bucketCheckInterval = bucketCheckInterval;
        this.bucketsBuilder = bucketsBuilder;
        setChainingStrategy( ChainingStrategy.ALWAYS);
    }

    /** Notifies a partition created. */
    protected abstract void partitionCreated(String partition);

    /**
     * Notifies a partition become inactive. A partition becomes inactive after all the records
     * received so far have been committed.
     */
    protected abstract void partitionInactive(String partition);

    /**
     * Notifies a new file has been opened.
     *
     * <p>Note that this does not mean that the file has been created in the file system. It is only
     * created logically and the actual file will be generated after it is committed.
     */
    protected abstract void onPartFileOpened(String partition, Path newPath);

    /** Commit up to this checkpoint id. */
    protected void commitUpToCheckpoint(long checkpointId) throws Exception {
        helper.commitUpToCheckpoint(checkpointId);
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        System.out.println( "initializeState" );
        super.initializeState(context);
        buckets = bucketsBuilder.createBuckets(getRuntimeContext().getIndexOfThisSubtask());

        // Set listener before the initialization of Buckets.
        buckets.setBucketLifeCycleListener(
                new BucketLifeCycleListener<IN, String> () {

                    @Override
                    public void bucketCreated(Bucket<IN, String> bucket) {
                        LakesoulAbstractStreamingWriter.this.partitionCreated(bucket.getBucketId());
                    }

                    @Override
                    public void bucketInactive(Bucket<IN, String> bucket) {
                        LakesoulAbstractStreamingWriter.this.partitionInactive(bucket.getBucketId());
                    }
                });

        buckets.setFileLifeCycleListener(LakesoulAbstractStreamingWriter.this::onPartFileOpened);

        helper =
                new StreamingFileSinkHelper<>(
                        buckets,
                        context.isRestored(),
                        context.getOperatorStateStore(),
                        getRuntimeContext().getProcessingTimeService(),
                        bucketCheckInterval);

        currentWatermark = Long.MIN_VALUE;
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        System.out.println( "spapshotstate" );
        super.snapshotState(context);
        helper.snapshotState(context.getCheckpointId());
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        super.processWatermark(mark);
        currentWatermark = mark.getTimestamp();
    }

    @Override
    public void processElement(StreamRecord<IN> element) throws Exception {
        System.out.println( "processElement" );
        helper.onElement(
                element.getValue(),
                getProcessingTimeService().getCurrentProcessingTime(),
                element.hasTimestamp() ? element.getTimestamp() : null,
                currentWatermark);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        System.out.println( "notifycheckpoint" );
        super.notifyCheckpointComplete(checkpointId);
        commitUpToCheckpoint(checkpointId);
    }

    @Override
    public void endInput() throws Exception {
        System.out.println( "endInput" );
        buckets.onProcessingTime(Long.MAX_VALUE);
        helper.snapshotState(Long.MAX_VALUE);
        output.emitWatermark(new Watermark(Long.MAX_VALUE));
        commitUpToCheckpoint(Long.MAX_VALUE);
    }

    @Override
    public void close() throws Exception {
        System.out.println( "close" );

        super.close();
        if (helper != null) {
            helper.close();
        }
    }
    @Override
    public void finish() throws Exception{
        System.out.println( "finish" );
    }
}
