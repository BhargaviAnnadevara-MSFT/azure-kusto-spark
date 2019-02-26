package com.microsoft.kusto.spark.datasource

import java.security.InvalidParameterException
import java.util.Locale

import com.microsoft.kusto.spark.datasink.KustoWriter
import com.microsoft.kusto.spark.utils.{KeyVaultUtils, KustoDataSourceUtils, KustoQueryUtils, KustoDataSourceUtils => KDSU}
import org.apache.spark.sql.sources.{BaseRelation, CreatableRelationProvider, DataSourceRegister, RelationProvider}
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}

class DefaultSource extends CreatableRelationProvider
  with RelationProvider with DataSourceRegister {
  var authenticationParameters: Option[KustoAuthentication] = _
  var kustoCoordinates: KustoCoordinates = _
  var keyVaultAuthentication: Option[KeyVaultAuthentication] = _

  override def createRelation(sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String], data: DataFrame): BaseRelation = {
    var writeOptions: WriteOptions = WriteOptions()

    (writeOptions, authenticationParameters, kustoCoordinates, keyVaultAuthentication) = KustoDataSourceUtils.parseSinkParameters(parameters, mode)

    if(keyVaultAuthentication.isDefined){
      val paramsFromKeyVault = KeyVaultUtils.getAadAppParametersFromKeyVault(keyVaultAuthentication.get)
      authenticationParameters = Some(KDSU.combineKeyVaultAndOptionsAuthentication(paramsFromKeyVault, authenticationParameters))
    }

    KustoWriter.write(
      None,
      data,
      kustoCoordinates,
      authenticationParameters.get,
      writeOptions)

    val limit = if (writeOptions.writeResultLimit.equalsIgnoreCase(KustoOptions.NONE_RESULT_LIMIT)) None else {
      try {
        Some(writeOptions.writeResultLimit.toInt)
      }
      catch {
        case _: Exception => throw new InvalidParameterException(s"KustoOptions.KUSTO_WRITE_RESULT_LIMIT is set to '${writeOptions.writeResultLimit}'. Must be either 'none' or an integer value")
      }
    }

    createRelation(sqlContext, adjustParametersForBaseRelation(parameters, limit))
  }

  def adjustParametersForBaseRelation(parameters: Map[String, String], limit: Option[Int]): Map[String, String] = {
    val readMode = parameters.get(KustoOptions.KUSTO_READ_MODE)
    val limitIsSmall = limit.isDefined && limit.get <= 200
    var adjustedParams = parameters

    if (readMode.isEmpty && limitIsSmall) {
      adjustedParams = parameters + (KustoOptions.KUSTO_READ_MODE -> "lean") + (KustoOptions.KUSTO_NUM_PARTITIONS -> "1")
    }
    else if (parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_SAS_URL).isEmpty && (parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_ACCOUNT_NAME).isEmpty ||
      parameters.get(KustoOptions.KUSTO_BLOB_CONTAINER).isEmpty ||
      parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_ACCOUNT_KEY).isEmpty)
    ) {
      if (readMode.isDefined && !readMode.get.equalsIgnoreCase("lean")) {
        throw new InvalidParameterException(s"Read mode is set to '${readMode.get}', but transient storage parameters are not provided")
      }
      adjustedParams = parameters + (KustoOptions.KUSTO_READ_MODE -> "lean") + (KustoOptions.KUSTO_NUM_PARTITIONS -> "1")
    }

    if (limit.isDefined) {
      adjustedParams + (KustoOptions.KUSTO_QUERY -> KustoQueryUtils.limitQuery(parameters(KustoOptions.KUSTO_TABLE), limit.get))
    } else {
      adjustedParams
    }
  }


  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation = {
    val requestedPartitions = parameters.get(KustoOptions.KUSTO_NUM_PARTITIONS)
    val readMode = parameters.getOrElse(KustoOptions.KUSTO_READ_MODE, "scale").toLowerCase(Locale.ROOT)
    val partitioningMode = parameters.get(KustoOptions.KUSTO_READ_PARTITION_MODE)
    val isLeanMode = readMode.equals("lean")

    val numPartitions = setNumPartitionsPerMode(sqlContext, requestedPartitions, isLeanMode, partitioningMode)
    if (!KustoOptions.supportedReadModes.contains(readMode)) {
      throw new InvalidParameterException(s"Kusto read mode must be one of ${KustoOptions.supportedReadModes.mkString(", ")}")
    }

    if (numPartitions != 1 && isLeanMode) {
      throw new InvalidParameterException(s"Reading in lean mode cannot be done on multiple partitions. Requested number of partitions: $numPartitions")
    }

    var storageSecretIsAccountKey = true
    var storageSecret = parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_ACCOUNT_KEY)
    if (storageSecret.isEmpty) {
      storageSecret = parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_SAS_URL)
      if (storageSecret.isDefined) storageSecretIsAccountKey = false
    }

    if(authenticationParameters.isEmpty){
      (authenticationParameters, kustoCoordinates, keyVaultAuthentication) = KDSU.parseSourceParameters(parameters)
    }

    val (kustoAuthentication, storageParameters): (KustoAuthentication, Option[StorageParameters]) =
      if (keyVaultAuthentication.isDefined) {
        // Get params from keyVault
        authenticationParameters = Some(KDSU.combineKeyVaultAndOptionsAuthentication(KeyVaultUtils.getAadAppParametersFromKeyVault(keyVaultAuthentication.get), authenticationParameters))

        if(isLeanMode){
          (authenticationParameters, None)
        } else {
          (authenticationParameters, Some(KDSU.combineKeyVaultAndOptionsStorageParams(
            parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_ACCOUNT_NAME),
            parameters.get(KustoOptions.KUSTO_BLOB_CONTAINER),
            storageSecret,
            storageSecretIsAccountKey,
            keyVaultAuthentication.get)))
        }
      } else {
        if(isLeanMode) {
          (authenticationParameters, None)
        } else {
          // Params passed from options
          (authenticationParameters, KDSU.getAndValidateTransientStorageParameters(
            parameters.get(KustoOptions.KUSTO_BLOB_STORAGE_ACCOUNT_NAME),
            parameters.get(KustoOptions.KUSTO_BLOB_CONTAINER),
            storageSecret,
            storageSecretIsAccountKey))
        }
    }

    KustoRelation(
      kustoCoordinates,
      kustoAuthentication,
      parameters.getOrElse(KustoOptions.KUSTO_QUERY, ""),
      isLeanMode,
      numPartitions,
      parameters.get(KustoOptions.KUSTO_PARTITION_COLUMN),
      partitioningMode,
      parameters.get(KustoOptions.KUSTO_CUSTOM_DATAFRAME_COLUMN_TYPES),
      storageParameters
    )(sqlContext.sparkSession)
  }

  private def setNumPartitionsPerMode(sqlContext: SQLContext, requestedNumPartitions: Option[String], isLeanMode: Boolean, partitioningMode: Option[String]): Int = {
    if (requestedNumPartitions.isDefined) requestedNumPartitions.get.toInt else {
      if (isLeanMode) 1 else {
        partitioningMode match {
          case Some("hash") => sqlContext.getConf("spark.sql.shuffle.partitions", "10").toInt
          // In "auto" mode we don't explicitly partition the data:
          // The data is exported and split to multiple files if required by Kusto 'export' command
          // The data is then read from the base directory for parquet files and partitioned by the parquet data source
          case _ => 1
        }
      }
    }
  }

  override def shortName(): String = "kusto"
}