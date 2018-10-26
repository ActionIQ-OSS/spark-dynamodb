package com.github.traviscrawford.spark.dynamodb

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.dynamodbv2.document.Table
import com.amazonaws.services.dynamodbv2.document.spec.{QuerySpec, ScanSpec}
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity
import com.amazonaws.services.dynamodbv2.xspec.ExpressionSpecBuilder
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter

private[dynamodb] trait BaseScanner {
  private val log = LoggerFactory.getLogger(this.getClass)

  def getTable(
    tableName: String,
    maybeCredentials: Option[String],
    maybeRegion: Option[String],
    maybeEndpoint: Option[String])
  : Table = {

    val builder = AmazonDynamoDBClientBuilder.standard()

    maybeCredentials match {
      case Some(credentialsClassName) =>
        log.info(s"Using AWSCredentialsProvider $credentialsClassName")
        val credentials = Class.forName(credentialsClassName)
          .newInstance().asInstanceOf[AWSCredentialsProvider]
        builder.withCredentials(credentials)
      case None => // pass
    }

    maybeRegion.foreach(builder.withRegion)
    maybeEndpoint.foreach(endpoint => {
      val endpointConfiguration = new EndpointConfiguration(endpoint, "us-west-2")
      builder.withEndpointConfiguration(endpointConfiguration) // for tests
    })
    val client = builder.build()
    new DynamoDB(client).getTable(tableName)
  }

  def getTable(config: ScanConfig): Table = {
    getTable(
      tableName = config.table,
      config.maybeCredentials,
      config.maybeRegion,
      config.maybeEndpoint)
  }

  def getQuerySpec(config: ScanConfig): QuerySpec = {
    val querySpec = new QuerySpec()
      .withMaxPageSize(config.pageSize)
      .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
    val exprSpecBuilder = config.maybeRequiredColumns
      .map(requiredColumns => new ExpressionSpecBuilder().addProjections(requiredColumns: _*))
    val parsedProjectionExpr = exprSpecBuilder.map(xSpecBuilder => xSpecBuilder.buildForScan())
    val projectionNameMap = parsedProjectionExpr.flatMap(projExpr => Option(projExpr.getNameMap))
      .map(_.asScala.toMap).getOrElse(Map.empty)
    val projectionValueMap = parsedProjectionExpr.flatMap(projExpr => Option(projExpr.getValueMap))
      .map(_.asScala.toMap).getOrElse(Map.empty)
    parsedProjectionExpr.foreach(expr =>
      querySpec.withProjectionExpression(expr.getProjectionExpression))

    // Parse any filter expression passed in as an option
    val parsedFilterExpr = config.maybeKeyExpression
      .map(filterExpression => ParsedFilterExpression(filterExpression))
    val filterNameMap = parsedFilterExpr.map(_.expressionNames).getOrElse(Map.empty)
    val filterValueMap = parsedFilterExpr.map(_.expressionValues).getOrElse(Map.empty)
    parsedFilterExpr.foreach(expr =>
      querySpec.withKeyConditionExpression(expr.expression))

    // Combine parsed name and value maps from the projections and filter expressions
    val nameMap = projectionNameMap ++ filterNameMap
    Option(nameMap).filter(_.nonEmpty).foreach(nMap => querySpec.withNameMap(nMap.asJava))
    val valueMap = projectionValueMap ++ filterValueMap.map{
      case (key, value) => key -> value.toString
    }
    Option(valueMap).filter(_.nonEmpty).foreach(vMap => querySpec.withValueMap(vMap.asJava))

    querySpec
  }

  def getScanSpec(config: ScanConfig): ScanSpec = {
    val scanSpec = new ScanSpec()
      .withMaxPageSize(config.pageSize)
      .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
      .withTotalSegments(config.totalSegments)
      .withSegment(config.segment)

    // Parse any projection expressions passed in
    val exprSpecBuilder = config.maybeRequiredColumns
      .map(requiredColumns => new ExpressionSpecBuilder().addProjections(requiredColumns: _*))
    val parsedProjectionExpr = exprSpecBuilder.map(xSpecBuilder => xSpecBuilder.buildForScan())
    val projectionNameMap = parsedProjectionExpr.flatMap(projExpr => Option(projExpr.getNameMap))
      .map(_.asScala.toMap).getOrElse(Map.empty)
    val projectionValueMap = parsedProjectionExpr.flatMap(projExpr => Option(projExpr.getValueMap))
      .map(_.asScala.toMap).getOrElse(Map.empty)
    parsedProjectionExpr.foreach(expr =>
      scanSpec.withProjectionExpression(expr.getProjectionExpression))

    // Parse any filter expression passed in as an option
    val parsedFilterExpr = config.maybeFilterExpression
      .map(filterExpression => ParsedFilterExpression(filterExpression))
    val filterNameMap = parsedFilterExpr.map(_.expressionNames).getOrElse(Map.empty)
    val filterValueMap = parsedFilterExpr.map(_.expressionValues).getOrElse(Map.empty)
    parsedFilterExpr.foreach(expr =>
      scanSpec.withFilterExpression(expr.expression))

    // Combine parsed name and value maps from the projections and filter expressions
    val nameMap = projectionNameMap ++ filterNameMap
    Option(nameMap).filter(_.nonEmpty).foreach(nMap => scanSpec.withNameMap(nMap.asJava))
    val valueMap = projectionValueMap ++ filterValueMap
    Option(valueMap).filter(_.nonEmpty).foreach(vMap => scanSpec.withValueMap(vMap.asJava))

    scanSpec
  }
}

private[dynamodb] case class ScanConfig(
  table: String,
  segment: Int,
  totalSegments: Int,
  pageSize: Int,
  maybeSchema: Option[StructType] = None,
  maybeRequiredColumns: Option[Array[String]] = None,
  maybeFilterExpression: Option[String] = None,
  maybeRateLimit: Option[Int] = None,
  maybeCredentials: Option[String] = None,
  maybeRegion: Option[String] = None,
  maybeEndpoint: Option[String] = None,
  maybeKeyExpression: Option[String] = None
)

