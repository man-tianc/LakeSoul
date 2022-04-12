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

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileStatus;
import org.apache.flink.core.fs.Path;
import org.apache.flink.lakesoul.tools.FlinkUtil;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.catalog.ObjectIdentifier;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.flink.table.filesystem.FileSystemFactory;

import static org.apache.flink.table.utils.PartitionPathUtils.extractPartitionSpecFromPath;
import static org.apache.flink.table.utils.PartitionPathUtils.generatePartitionPath;

public class DataInfoCommitter extends AbstractStreamOperator<Void>
        implements OneInputStreamOperator<DataInfo, Void> {

    private static final long serialVersionUID = 1L;

    private final Configuration conf;

    private final Path locationPath;

    private final ObjectIdentifier tableIdentifier;

    private final List<String> partitionKeys;

    private final FileSystemFactory fsFactory;

    private transient PartitionTrigger trigger;

    private transient LakesoulTaskCheck taskTracker;

    private transient long currentWatermark;

    public DataInfoCommitter(
            Path locationPath,
            ObjectIdentifier tableIdentifier,
            List<String> partitionKeys,
            FileSystemFactory fsFactory,
            Configuration conf) {
        this.locationPath = locationPath;
        this.tableIdentifier = tableIdentifier;
        this.partitionKeys = partitionKeys;
        this.fsFactory = fsFactory;
        this.conf = conf;
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);
        this.currentWatermark = Long.MIN_VALUE;
        this.trigger =
                PartitionTrigger.create(
                        context.isRestored(),
                        context.getOperatorStateStore()
                     );

    }

    @Override
    public void processElement(StreamRecord<DataInfo> element) throws Exception {
        DataInfo message = element.getValue();
        for (String partition : message.getPartitions()) {
            trigger.addPartition(partition);
        }
        if (taskTracker == null) {
            taskTracker = new LakesoulTaskCheck(message.getNumberOfTasks());
        }
        boolean needCommit = taskTracker.add(message.getCheckpointId(), message.getTaskId());
        if (needCommit) {
            commitPartitions(message);
        }
    }

    private void commitPartitions(DataInfo element) throws Exception {
        long checkpointId = element.getCheckpointId ();
        List<String> partitions =
                checkpointId == Long.MAX_VALUE
                        ? trigger.endInput()
                        : trigger.committablePartitions(checkpointId);
        if (partitions.isEmpty()) {
            return;
        }
        String filenamePrefix = element.getTaskDataPath();

        LakesoulTableMetaStore ltms = new LakesoulTableMetaStore(tableIdentifier);
        int fileNumInPartitions=0;
        for (String partition : partitions) {
            Path path = new Path(locationPath, partition);
            org.apache.flink.core.fs.FileStatus[] files =  path.getFileSystem().listStatus(path);
            for(FileStatus fs:files){
                if(!fs.isDir()){
                    String onepath  = fs.getPath().toString();
                    if(onepath.contains( filenamePrefix )){
                        long len=fs.getLen();
                        long modify_time=fs.getModificationTime();
                        ltms.withData( partition ,new LakesoulTableData( fs.getPath().toString(),modify_time,len,partition ));
                    }
                    fileNumInPartitions++;
                }
            }
        }
        if(fileNumInPartitions>0){
            ltms.commit();
        }else{
            ltms=null;
        }
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        super.processWatermark(mark);
        this.currentWatermark = mark.getTimestamp();
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        trigger.snapshotState(context.getCheckpointId(), currentWatermark);
    }


}