/*
 * Copyright [2022] [DMetaSoul Team]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.lakesoul.commands

import com.dmetasoul.lakesoul.meta.MetaVersion
import org.apache.hadoop.fs.Path
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.PredicateHelper
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.execution.datasources.v2.merge.MergeDeltaParquetScan
import org.apache.spark.sql.execution.datasources.v2.parquet.ParquetScan
import org.apache.spark.sql.execution.datasources.v2.{DataSourceV2Relation, DataSourceV2ScanRelation}
import org.apache.spark.sql.functions.expr
import org.apache.spark.sql.lakesoul.catalog.LakeSoulTableV2
import org.apache.spark.sql.lakesoul.exception.LakeSoulErrors
import org.apache.spark.sql.lakesoul.utils.{DataFileInfo, PartitionInfo, SparkUtil}
import org.apache.spark.sql.lakesoul.{BatchDataSoulFileIndexV2, SnapshotManagement, TransactionCommit}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.sql.{Dataset, Row, SparkSession}

import scala.collection.JavaConversions._


case class CompactionCommand(snapshotManagement: SnapshotManagement,
                             conditionString: String,
                             force: Boolean,
                             mergeOperatorInfo: Map[String, String],
                             hiveTableName: String = "")
  extends RunnableCommand with PredicateHelper with Logging {


  /**
    * now：
    * 1. delta file num exceed threshold value
    * 2. this partition not been compacted, and last update time exceed threshold value
    */
  def filterPartitionNeedCompact(spark: SparkSession,
                                 force: Boolean,
                                 partitionInfo: PartitionInfo): Boolean = {
    return  partitionInfo.read_files.size >=1
//      val timestampLimit = System.currentTimeMillis() - spark.conf.get(LakeSoulSQLConf.COMPACTION_TIME)
//    if (force) {
//      !partitionInfo.be_compacted
//    } else {
//      if (partitionInfo.delta_file_num >= spark.conf.get(LakeSoulSQLConf.MAX_DELTA_FILE_NUM)) {
//        true
//      } else if (partitionInfo.last_update_timestamp <= timestampLimit
//        && !partitionInfo.be_compacted) {
//        true
//      } else {
//        false
//      }
//    }

  }

  def executeCompaction(spark: SparkSession, tc: TransactionCommit, files: Seq[DataFileInfo]): Unit = {
    val fileIndex = BatchDataSoulFileIndexV2(spark, snapshotManagement, files)
    val table = LakeSoulTableV2(
      spark,
      new Path(snapshotManagement.table_path),
      None,
      None,
      Option(fileIndex),
      Option(mergeOperatorInfo)
    )
    val option = new CaseInsensitiveStringMap(
      Map("basePath" -> tc.tableInfo.table_path_s.get, "isCompaction" -> "true"))

    val scan = table.newScanBuilder(option).build()
    if(scan.isInstanceOf[ParquetScan]){
      throw LakeSoulErrors.CompactionException(table_name = table.name())
    }
    val newReadFiles = scan.asInstanceOf[MergeDeltaParquetScan].newFileIndex.getFileInfo(Nil)

    val v2Relation = DataSourceV2Relation(
      table,
      table.schema().toAttributes,
      None,
      None,
      option
    )

    val compactDF = Dataset.ofRows(
      spark,
      DataSourceV2ScanRelation(
        v2Relation,
        scan,
        table.schema().toAttributes
      )
    )

    tc.setReadFiles(newReadFiles)
    tc.setCommitType("compaction")
    val (newFiles, path) = tc.writeFiles(compactDF, isCompaction = true)
    tc.commit(newFiles, newReadFiles)
    if (!hiveTableName.isEmpty) {
      SparkUtil.spark.sql(s"ALTER TABLE $hiveTableName DROP IF EXISTS partition($conditionString)")
      SparkUtil.spark.sql(s"ALTER TABLE $hiveTableName ADD partition($conditionString) location '${path.toString}/$conditionString'")
    }

    logInfo("=========== Compaction Success!!! ===========")
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val condition = conditionString match {
      case "" => None
      case _: String => Option(expr(conditionString).expr)
    }
    //when condition is defined, only one partition need compaction,
    //else we will check whole table
    if (condition.isDefined) {
      val targetOnlyPredicates =
        splitConjunctivePredicates(condition.get)

      snapshotManagement.withNewTransaction(tc => {
        val files = tc.filterFiles(targetOnlyPredicates)

        //ensure only one partition execute compaction command
        //todo range_partitions
        val partitionSet = files.map(_.range_partitions).toSet
//        val partitionSet = files.map(_.range_id).toSet
        if (partitionSet.isEmpty) {
          throw LakeSoulErrors.partitionColumnNotFoundException(condition.get, 0)
        } else if (partitionSet.size > 1) {
          throw LakeSoulErrors.partitionColumnNotFoundException(condition.get, partitionSet.size)
        }

        val range_value = partitionSet.head
        val table_id = tc.tableInfo.table_id
        val range_id = tc.snapshot.getPartitionInfoArray
          .filter(part => part.range_value.equals(range_value))
          .head.range_value

//        val partitionInfo = MetaVersion.getSinglePartitionInfo(table_id, range_value, range_id)

        lazy val hasNoDeltaFile = if (force) {
          false
        } else {
          files.groupBy(_.file_bucket_id).forall(_._2.size == 1)
        }

        if (hasNoDeltaFile) {
          logInfo("== Compaction: This partition has been compacted or has no delta file.")
        } else {
          executeCompaction(sparkSession, tc, files)
        }

      })
    } else {

      val allInfo = MetaVersion.getAllPartitionInfo(snapshotManagement.getTableInfoOnly.table_id)
      val partitionsNeedCompact = allInfo
        .filter(filterPartitionNeedCompact(sparkSession, force, _))

      partitionsNeedCompact.foreach(part => {
        snapshotManagement.withNewTransaction(tc => {
          val files = tc.getCompactionPartitionFiles(part)

          val hasNoDeltaFile = if (force) {
            false
          } else {
            files.groupBy(_.file_bucket_id).forall(_._2.size == 1)
          }
          if (hasNoDeltaFile) {
            logInfo(s"== Partition ${part.range_value} has no delta file.")
          } else {
            executeCompaction(sparkSession, tc, files)
          }
        })
      })


    }


    Seq.empty
  }


}