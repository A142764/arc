package au.com.agl.arc.extract

import java.lang._
import java.net.URI
import scala.collection.JavaConverters._

import com.databricks.spark.xml._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel

import au.com.agl.arc.api._
import au.com.agl.arc.api.API._
import au.com.agl.arc.util._

object XMLExtract {

  def extract(extract: XMLExtract)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): DataFrame = {
    import spark.implicits._
    val startTime = System.currentTimeMillis() 
    val stageDetail = new java.util.HashMap[String, Object]()
    val contiguousIndex = extract.contiguousIndex.getOrElse(true)
    stageDetail.put("type", extract.getType)
    stageDetail.put("name", extract.name)
    stageDetail.put("input", extract.input.toString)  
    stageDetail.put("outputView", extract.outputView)  
    stageDetail.put("persist", Boolean.valueOf(extract.persist))
    stageDetail.put("contiguousIndex", Boolean.valueOf(contiguousIndex))

    val options = ConfigUtils.paramsToOptions(extract.params, "rowTag" :: Nil)

    stageDetail.put("options", options.asJava)

    logger.info()
      .field("event", "enter")
      .map("stage", stageDetail)      
      .log()    

    CloudUtils.setHadoopConfiguration(extract.authentication)

    val df = try {
      // read using sparkContext textFile to allow automatic handling of Zip compression

      val oldDelimiter = spark.sparkContext.hadoopConfiguration.get("textinputformat.record.delimiter")
      val newDelimiter = s"${0x0 : Char}"
      // temporarily remove the delimiter so all the data is loaded as a single line
      spark.sparkContext.hadoopConfiguration.set("textinputformat.record.delimiter", newDelimiter)      

      val textFile = spark.sparkContext.textFile(extract.input.toString)
      val xmlReader = new XmlReader

      val xml = extract.cols match {
        case Nil => {
          extract.params.get("rowTag") match {
            case Some(rowTag) => xmlReader.withRowTag(rowTag).xmlRdd(spark.sqlContext, textFile)
            case None => xmlReader.xmlRdd(spark.sqlContext, textFile)
          } 
        }
        case cols => { 
          val schema = Extract.toStructType(cols)
          extract.params.get("rowTag") match {
            case Some(rowTag) => xmlReader.withSchema(schema).withRowTag(rowTag).xmlRdd(spark.sqlContext, textFile)
            case None => xmlReader.withSchema(schema).xmlRdd(spark.sqlContext, textFile)
          }           
        }                
      }

      // reset delimiter
      if (oldDelimiter == null) {
        spark.sparkContext.hadoopConfiguration.unset("textinputformat.record.delimiter")              
      } else {
        spark.sparkContext.hadoopConfiguration.set("textinputformat.record.delimiter", oldDelimiter)        
      }

      xml
    } catch {
      case e: org.apache.hadoop.mapreduce.lib.input.InvalidInputException if (e.getMessage.contains("matches 0 files")) => {
        spark.emptyDataFrame
      }         
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stageDetail          
      }      
    }

    // if incoming dataset has 0 columns then create empty dataset with correct schema
    val emptyDataframeHandlerDF = if (df.schema.length == 0) {
      val schema = extract.cols match {
        case Nil => throw new Exception(s"XMLExtract has produced 0 columns and no schema has been provided to create an empty dataframe.") with DetailException {
          override val detail = stageDetail          
        }
        case cols => Extract.toStructType(cols)
      }
      spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
    } else {
      df
    }

    // add meta columns including sequential index
    val enrichedDF = ExtractUtils.addMetadata(emptyDataframeHandlerDF, contiguousIndex)
         
    // repartition to distribute rows evenly
    val repartitionedDF = extract.partitionBy match {
      case Nil => { 
        extract.numPartitions match {
          case Some(numPartitions) => enrichedDF.repartition(numPartitions)
          case None => enrichedDF
        }   
      }
      case partitionBy => {
        // create a column array for repartitioning
        val partitionCols = partitionBy.map(col => df(col))
        extract.numPartitions match {
          case Some(numPartitions) => enrichedDF.repartition(numPartitions, partitionCols:_*)
          case None => enrichedDF.repartition(partitionCols:_*)
        }
      }
    } 
    repartitionedDF.createOrReplaceTempView(extract.outputView)

    stageDetail.put("inputFiles", Integer.valueOf(repartitionedDF.inputFiles.length))
    stageDetail.put("outputColumns", Integer.valueOf(repartitionedDF.schema.length))
    stageDetail.put("numPartitions", Integer.valueOf(repartitionedDF.rdd.partitions.length))

    if (extract.persist) {
      repartitionedDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
      stageDetail.put("records", Long.valueOf(repartitionedDF.count)) 
    }    

    logger.info()
      .field("event", "exit")
      .field("duration", System.currentTimeMillis() - startTime)
      .map("stage", stageDetail)      
      .log() 

    repartitionedDF
  }

}

