package com.ebiznext.comet.job.index.bqload

import com.ebiznext.comet.config.Settings
import com.ebiznext.comet.utils.{JobBase, JobResult, Utils}
import com.google.cloud.ServiceOptions
import com.google.cloud.bigquery.JobInfo.{CreateDisposition, WriteDisposition}
import com.google.cloud.bigquery.QueryJobConfiguration.Priority
import com.google.cloud.bigquery._
import com.typesafe.scalalogging.StrictLogging

import java.util.UUID
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

case class BigQueryJobResult(tableResult: scala.Option[TableResult]) extends JobResult

class BigQueryNativeJob(
  override val cliConfig: BigQueryLoadConfig,
  sql: String,
  udf: scala.Option[String]
)(implicit val settings: Settings)
    extends JobBase
    with BigQueryJobBase {

  override def name: String = s"bqload-${cliConfig.outputDataset}-${cliConfig.outputTable}"

  override val projectId: String = ServiceOptions.getDefaultProjectId

  logger.info(s"BigQuery Config $cliConfig")

  def runInteractiveQuery(): BigQueryJobResult = {
    val queryConfig: QueryJobConfiguration.Builder =
      QueryJobConfiguration
        .newBuilder(sql)
        .setAllowLargeResults(true)
    logger.info(s"Running BQ Query $sql")
    val queryConfigWithUDF = addUDFToQueryConfig(queryConfig)
    val results = bigquery.query(queryConfigWithUDF.setPriority(Priority.INTERACTIVE).build())
    logger.info(
      s"Query large results performed successfully: ${results.getTotalRows} rows inserted."
    )
    BigQueryJobResult(Some(results))
  }

  private def addUDFToQueryConfig(
    queryConfig: QueryJobConfiguration.Builder
  ): QueryJobConfiguration.Builder = {
    val queryConfigWithUDF = udf
      .map { udf =>
        queryConfig.setUserDefinedFunctions(List(UserDefinedFunction.fromUri(udf)).asJava)
      }
      .getOrElse(queryConfig)
    queryConfigWithUDF
  }

  /** Just to force any spark job to implement its entry point within the "run" method
    *
    * @return
    *   : Spark Session used for the job
    */
  override def run(): Try[JobResult] = {
    Try {
      val targetDataset = getOrCreateDataset()
      val queryConfig: QueryJobConfiguration.Builder =
        QueryJobConfiguration
          .newBuilder(sql)
          .setCreateDisposition(CreateDisposition.valueOf(cliConfig.createDisposition))
          .setWriteDisposition(WriteDisposition.valueOf(cliConfig.writeDisposition))
          .setDefaultDataset(targetDataset.getDatasetId)
          .setPriority(Priority.INTERACTIVE)
          .setAllowLargeResults(true)

      val queryConfigWithPartition = cliConfig.outputPartition match {
        case Some(partitionField) =>
          // Generating schema from YML to get the descriptions in BQ
          val partitioning =
            timePartitioning(partitionField, cliConfig.days, cliConfig.requirePartitionFilter)
              .build()
          queryConfig.setTimePartitioning(partitioning)
        case None =>
          queryConfig
      }
      val queryConfigWithClustering = cliConfig.outputClustering match {
        case Nil =>
          queryConfigWithPartition
        case fields =>
          val clustering = Clustering.newBuilder().setFields(fields.asJava).build()
          queryConfigWithPartition.setClustering(clustering)
      }
      val queryConfigWithUDF = addUDFToQueryConfig(queryConfigWithClustering)
      logger.info(s"Executing BQ Query $sql")
      val results = bigquery.query(queryConfigWithUDF.setDestinationTable(tableId).build())
      logger.info(
        s"Query large results performed successfully: ${results.getTotalRows} rows inserted."
      )
      BigQueryJobResult(Some(results))
    }
  }

  def runBatchQuery(): Try[JobResult] = {
    Try {
      val bigquery: BigQuery = BigQueryOptions.getDefaultInstance.getService
      getOrCreateDataset()
      val jobId = JobId
        .newBuilder()
        .setJob(
          UUID.randomUUID.toString
        ) // Run at batch priority, which won't count toward concurrent rate limit.
        .setLocation(cliConfig.getLocation())
        .build()
      val queryConfig =
        QueryJobConfiguration
          .newBuilder(sql)
          .setPriority(Priority.BATCH)
          .setUseLegacySql(false)
          .build()
      logger.info(s"Executing BQ Query $sql")
      bigquery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build)
      logger.info(
        s"Batch query wth jobId $jobId sent to BigQuery "
      )
      BigQueryJobResult(None)
    }
  }

}

object BigQueryNativeJob extends StrictLogging {
  def createTable(datasetName: String, tableName: String, schema: Schema): Unit = {
    Try {
      val bigquery = BigQueryOptions.getDefaultInstance.getService
      val tableId = TableId.of(datasetName, tableName)
      val table = scala.Option(bigquery.getTable(tableId))
      table match {
        case Some(tbl) if tbl.exists() =>
        case _ =>
          val tableDefinition = StandardTableDefinition.of(schema)
          val tableInfo = TableInfo.newBuilder(tableId, tableDefinition).build
          bigquery.create(tableInfo)
          logger.info(s"Table $datasetName.$tableName created successfully")
      }
    } match {
      case Success(_) =>
      case Failure(e) =>
        logger.info(s"Table $datasetName.$tableName was not created.")
        Utils.logException(logger, e)
    }
  }

  @deprecated("Views are now created using the syntax WTH ... AS ...", "0.1.25")
  def createViews(views: Map[String, String], udf: scala.Option[String]) = {
    val bigquery: BigQuery = BigQueryOptions.getDefaultInstance.getService
    views.foreach { case (key, value) =>
      val viewQuery: ViewDefinition.Builder =
        ViewDefinition.newBuilder(value).setUseLegacySql(false)
      val viewDefinition = udf
        .map { udf =>
          viewQuery
            .setUserDefinedFunctions(List(UserDefinedFunction.fromUri(udf)).asJava)
        }
        .getOrElse(viewQuery)
      val tableId = BigQueryJobBase.extractProjectDatasetAndTable(key)
      val viewRef = scala.Option(bigquery.getTable(tableId))
      if (viewRef.isEmpty) {
        logger.info(s"View $tableId does not exist, creating it!")
        bigquery.create(TableInfo.of(tableId, viewDefinition.build()))
        logger.info(s"View $tableId created")
      } else {
        logger.info(s"View $tableId already exist")
      }
    }
  }
}
