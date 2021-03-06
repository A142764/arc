package au.com.agl.arc.transform

import java.lang._
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.storage.StorageLevel

import au.com.agl.arc.api.API._
import au.com.agl.arc.util._

object MetadataFilterTransform {

  def transform(transform: MetadataFilterTransform)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Option[DataFrame] = {
    val startTime = System.currentTimeMillis() 
    val stageDetail = new java.util.HashMap[String, Object]()
    stageDetail.put("type", transform.getType)
    stageDetail.put("name", transform.name)

    // inject sql parameters
    val stmt = SQLUtils.injectParameters(transform.sql, transform.sqlParams)
    stageDetail.put("sql", stmt)

    logger.info()
      .field("event", "enter")
      .map("stage", stageDetail)      
      .log()      
    
    val df = spark.table(transform.inputView)
    val metadataSchemaDF = MetadataUtils.createMetadataDataframe(df)
    metadataSchemaDF.createOrReplaceTempView("metadata")

    val filterDF = try {
      spark.sql(stmt)
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stageDetail          
      }      
    }  

    if (!filterDF.columns.contains("name")) {
      throw new Exception("result does not contain field 'name' so cannot be filtered") with DetailException {
        override val detail = stageDetail          
      }    
    }

    // get fields that meet condition from query result
    val inputFields = df.columns.toSet
    val includeColumns = filterDF.collect.map(field => { field.getString(field.fieldIndex("name")) }).toSet
    val excldueColumns = inputFields.diff(includeColumns)

    stageDetail.put("includedColumns", includeColumns.asJava)
    stageDetail.put("excludedColumns", excldueColumns.asJava)

    // drop fields in the excluded set
    val transformedDF = df.drop(excldueColumns.toList:_*)
    transformedDF.createOrReplaceTempView(transform.outputView)

    if (transform.persist) {
      transformedDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
      stageDetail.put("records", Long.valueOf(transformedDF.count)) 
    }    

    logger.info()
      .field("event", "exit")
      .field("duration", System.currentTimeMillis() - startTime)
      .map("stage", stageDetail)      
      .log()  

    Option(transformedDF)
  }

}
