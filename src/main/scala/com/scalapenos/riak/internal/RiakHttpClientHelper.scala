/*
 * Copyright (C) 2012-2013 Age Mooij (http://scalapenos.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalapenos.riak
package internal

import akka.actor._
import akka.actor.ActorDSL._
import play.api.libs.iteratee.Step.Done
import play.api.libs.iteratee._
import spray.http._
import spray.client.pipelining._
import spray.http.parser.HttpParser
import spray.json._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.collection.generic.CanBuildFrom

//Temporary fix for spray 1.3.1_2.11
import java.io.{ ByteArrayOutputStream, ByteArrayInputStream }
import org.jvnet.mimepull.{ MIMEMessage, MIMEConfig }
import org.parboiled.common.FileUtils
import scala.collection.JavaConverters._
import spray.http.parser.HttpParser
import spray.util._
import MediaTypes._
import MediaRanges._
import HttpHeaders._
import HttpCharsets._
import spray.httpx.unmarshalling.Unmarshaller

import org.slf4j.{Logger, LoggerFactory}


private[riak] object RiakHttpClientHelper {
  import spray.http.HttpEntity
  import spray.httpx.marshalling._

  /**
   * Spray Marshaller for turning RiakValue instances into HttpEntity instances so they can be sent to Riak.
   */
  implicit val RiakValueMarshaller: Marshaller[RiakValue] = new Marshaller[RiakValue] {
    def apply(riakValue: RiakValue, ctx: MarshallingContext) {
      ctx.marshalTo(HttpEntity(riakValue.contentType, riakValue.data.getBytes(riakValue.contentType.charset.nioCharset)))
    }
  }
}

private[riak] class RiakHttpClientHelper(system: ActorSystem) extends RiakUriSupport with RiakIndexSupport with DateTimeSupport {
  import scala.concurrent.Future
  import scala.concurrent.Future._
  import spray.http.{HttpEntity, HttpHeader, HttpResponse}
  import spray.http.StatusCodes._
  import spray.http.HttpHeaders._

  import org.slf4j.LoggerFactory

  import SprayClientExtras._
  import SprayJsonSupport._
  import RiakHttpHeaders._
  import RiakHttpClientHelper._

  import system.dispatcher

  private implicit val sys = system
  private val settings = RiakClientExtension(system).settings

  private val log = LoggerFactory.getLogger("scala-riak-client")

  // ==========================================================================
  // Main HTTP Request Implementations
  // ==========================================================================

  def ping(server: RiakServerInfo): Future[Boolean] = {
    httpRequest(Get(PingUri(server))).map { response =>
      response.status match {
        case OK    => true
        case other => throw new OperationFailed(s"Ping on server '$server' produced an unexpected response code '$other'.")
      }
    }
  }

  def fetch(server: RiakServerInfo, bucket: String, bucketType:String, key: String, resolver: RiakConflictsResolver): Future[Option[RiakValue]] = {
    httpRequest(Get(KeyUri(server, bucket, bucketType, key))).flatMap { response =>
      response.status match {
        case OK              => successful(toRiakValue(response))
        case NotFound        => successful(None)
        case MultipleChoices => resolveConflict(server, bucket, bucketType, key, response, resolver).map(Some(_))
        case other           => throw new BucketOperationFailed(s"Fetch for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
        // TODO: case NotModified => successful(None)
      }
    }
  }

  def fetch(server: RiakServerInfo, bucket: String, bucketType:String, index: RiakIndex, resolver: RiakConflictsResolver): Future[List[RiakValue]] = {
    httpRequest(Get(IndexUri(server, bucket, bucketType, index))).flatMap { response =>
      response.status match {
        case OK         => fetchWithKeysReturnedByIndexLookup(server, bucket, bucketType, response, resolver)
        case BadRequest => throw new ParametersInvalid(s"""Invalid index name ("${index.fullName}") or value ("${index.value}").""")
        case other      => throw new BucketOperationFailed(s"""Fetch for index "${index.fullName}" with value "${index.value}" in bucket "${bucket}" produced an unexpected response code: ${other}.""")
      }
    }
  }

  def fetch(server: RiakServerInfo, bucket: String, bucketType:String, indexRange: RiakIndexRange, resolver: RiakConflictsResolver): Future[List[RiakValue]] = {
    httpRequest(Get(IndexRangeUri(server, bucket, bucketType, indexRange))).flatMap { response =>
      response.status match {
        case OK         => fetchWithKeysReturnedByIndexLookup(server, bucket, bucketType, response, resolver)
        case BadRequest => throw new ParametersInvalid(s"""Invalid index name ("${indexRange.fullName}") or range ("${indexRange.start}" to "${indexRange.start}").""")
        case other      => throw new BucketOperationFailed(s"""Fetch for index "${indexRange.fullName}" with range "${indexRange.start}" to "${indexRange.start}" in bucket "${bucket}" produced an unexpected response code: ${other}.""")
      }
    }
  }

  def store(server: RiakServerInfo, bucket: String, bucketType:String, key: String, value: RiakValue, resolver: RiakConflictsResolver): Future[Unit] = {
    val request = createStoreHttpRequest(value)
    request(Put(KeyUri(server, bucket, bucketType, key), value)).flatMap { response =>
      response.status match {
        case NoContent => successful(())
        case other     => throw new BucketOperationFailed(s"Store of value '$value' for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
        // TODO: case PreconditionFailed => ... // needed when we support conditional request semantics
      }
    }
  }

  def storeAndFetch(server: RiakServerInfo, bucket: String, bucketType:String, key: String, value: RiakValue, resolver: RiakConflictsResolver): Future[RiakValue] = {
    val request = createStoreHttpRequest(value)
    request(Put(KeyUri(server, bucket, bucketType, key, StoreQueryParameters(true)), value)).flatMap { response =>
      response.status match {
        case OK              => successful(toRiakValue(response).getOrElse(throw new BucketOperationFailed(s"Store of value '$value' for key '$key' in bucket '$bucket' produced an unparsable reponse.")))
        case MultipleChoices => resolveConflict(server, bucket, bucketType, key, response, resolver)
        case other           => throw new BucketOperationFailed(s"Store of value '$value' for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
        // TODO: case PreconditionFailed => ... // needed when we support conditional request semantics
      }
    }
  }

  def delete(server: RiakServerInfo, bucket: String, bucketType:String, key: String): Future[Unit] = {
    httpRequest(Delete(KeyUri(server, bucket, bucketType, key))).map { response =>
      response.status match {
        case NoContent => ()
        case NotFound  => ()
        case other     => throw new BucketOperationFailed(s"Delete for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
      }
    }
  }

  def getBucketProperties(server: RiakServerInfo, bucket:String, bucketType:String): Future[RiakBucketProperties] = {
    import spray.httpx.unmarshalling._
    httpRequest(Get(BucketPropertiesUri(server, bucket, bucketType))).map { response =>
      response.status match {
        case OK => response.entity.as[RiakBucketProperties] match {
          case Right(properties) => properties
          case Left(error)       => throw new BucketOperationFailed(s"Fetching properties of '$bucket' failed because the response entity could not be parsed.")
        }
        case other => throw new BucketOperationFailed(s"Fetching properties of '$bucketType' produced an unexpected response code '$other'.")
      }
    }
  }

  def setBucketProperties(server: RiakServerInfo, bucket:String, bucketType:String, newProperties: Set[RiakBucketProperty[_]]): Future[Unit] = {
    import spray.json._
    val properties = JsObject("props" -> JsObject(newProperties.map(property => (property.name -> property.json)).toMap))
    httpRequest(Put(BucketPropertiesUri(server, bucket, bucketType), properties)).map { response =>
      response.status match {
        case NoContent            => successful(())
        case BadRequest           => throw new ParametersInvalid(s"Setting properties of '$bucket' failed because the http request contained invalid data.")
        case UnsupportedMediaType => throw new BucketOperationFailed(s"Setting properties of '$bucket' failed because the content type of the http request was not 'application/json'.")
        case other                => throw new BucketOperationFailed(s"Setting properties of '$bucket' produced an unexpected response code '$other'.")
      }
    }
  }

  def getBucketTypeProperties(server: RiakServerInfo, bucketType: String): Future[RiakBucketProperties] = {
    import spray.httpx.unmarshalling._

    httpRequest(Get(BucketTypePropertiesUri(server, bucketType))).map { response =>
      response.status match {
        case OK => response.entity.as[RiakBucketProperties] match {
          case Right(properties) => properties
          case Left(error)       => throw new BucketOperationFailed(s"Fetching properties of '$bucketType' failed because the response entity could not be parsed.")
        }
        case other => throw new BucketOperationFailed(s"Fetching properties of '$bucketType' produced an unexpected response code '$other'.")
      }
    }
  }

  def setBucketTypeProperties(server: RiakServerInfo, bucketType: String, newProperties: Set[RiakBucketProperty[_]]): Future[Unit] = {
    import spray.json._

    val properties = JsObject("props" -> JsObject(newProperties.map(property => (property.name -> property.json)).toMap))

    httpRequest(Put(BucketTypePropertiesUri(server, bucketType), properties)).map { response =>
      response.status match {
        case NoContent            => successful(())
        case BadRequest           => throw new ParametersInvalid(s"Setting properties of '$bucketType' failed because the http request contained invalid data.")
        case UnsupportedMediaType => throw new BucketOperationFailed(s"Setting properties of '$bucketType' failed because the content type of the http request was not 'application/json'.")
        case other                => throw new BucketOperationFailed(s"Setting properties of '$bucketType' produced an unexpected response code '$other'.")
      }
    }
  }

  def setBucketSearchIndex(server: RiakServerInfo, bucket:String, bucketType:String, searchIndex:RiakSearchIndex): Future[Boolean] = {
    val properties = JsObject("props" -> JsObject("search_index" -> JsString(searchIndex.name)))
    httpRequest(Put(BucketPropertiesUri(server, bucket, bucketType), properties)).map { response =>
      response.status match {
        case NoContent            => true
        case BadRequest           => throw new ParametersInvalid(s"Setting properties of '$bucketType' failed with message: ${response.entity.asString}")
        case UnsupportedMediaType => throw new BucketOperationFailed(s"Setting search_index of '$bucketType' failed because the content type of the http request was not 'application/json'.")
        case other                => throw new BucketOperationFailed(s"Setting search_index of '$bucketType' produced an unexpected response code '$other'.")
      }
    }
  }

  def setBucketTypeSearchIndex(server: RiakServerInfo, bucketType:String, searchIndex:RiakSearchIndex): Future[Boolean] = {
    val properties = JsObject("props" -> JsObject("search_index" -> JsString(searchIndex.name)))
    httpRequest(Put(BucketTypePropertiesUri(server, bucketType), properties)).map { response =>
      response.status match {
        case NoContent            => true
        case BadRequest           => throw new BucketTypeOperationFailed(s"Setting properties of '$bucketType' failed with the following message: ${response.entity.asString}")
        case UnsupportedMediaType => throw new BucketTypeOperationFailed(s"Setting search_index of '$bucketType' failed because the content type of the http request was not 'application/json'.")
        case other                => throw new BucketTypeOperationFailed(s"Setting search_index of '$bucketType' produced an unexpected response code '$other'.")
      }
    }
  }

  /* Search */
  def search(server: RiakServerInfo, index: RiakSearchIndex, solrQuery:RiakSearchQuery): Future[RiakSearchResult] = {
    val query:Map[String, String] = solrQuery.m.toMap
    httpRequest(Get(SearchSolrUri(server, index.name, SolrQueryParameters(query)))).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"Invalid search or params (${query.toString}) ")
        case OK         => successful(toRiakSearch(response, server))
        case other      => throw new OperationFailed(s"Solr search query(${query.toString}) produced an unexpected response code '$other'.")
      }
    }
  }

  private def searchForBucketsAndBucketTypes( server: RiakServerInfo, bucket: RiakBucketBasicProperties, solrQuery:RiakSearchQuery): Future[RiakSearchResult] = {
    bucket.getSearchIndex.flatMap{x => x match {
      case Some(index) =>
        if(index == RiakNoSearchIndex.name)
          throw new OperationFailed(s"${bucket.name} has a ${RiakNoSearchIndex.name} index associated which is not allowed for search")
        else
          getSearchIndex(server, index).flatMap(riakSearchIndex => search(server, riakSearchIndex, solrQuery))
      case None => throw new OperationFailed(s"${bucket.name} do not have an index associated")
    }}
  }

  def search(server: RiakServerInfo, bucket: RiakBucket, solrQuery:RiakSearchQuery): Future[RiakSearchResult] =
    searchForBucketsAndBucketTypes(server, bucket, solrQuery)

  def search(server: RiakServerInfo, bucketType: RiakBucketType, solrQuery:RiakSearchQuery): Future[RiakSearchResult] =
    searchForBucketsAndBucketTypes(server, bucketType, solrQuery)

  def createSearchIndex(server: RiakServerInfo, name:String, schema:String, nVal:Int): Future[RiakSearchIndex] = {

    val props = JsObject("schema" -> JsString(schema), "n_val" -> JsNumber(nVal))
    httpRequest(Put(SearchIndexUri(server, name), props)).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem creating the search index because the http request contained invalid data.")
        case NoContent  => successful(RiakSearchIndex(name, nVal, schema))
        case Conflict   => throw new OperationFailed(s"There was a conflict trying to create the index. Message: '${response.entity.asString}'.")
        case other      => throw new OperationFailed(s"There was a problem creating the search index '$other'.")
      }
    }
  }

  def deleteSearchIndex(server: RiakServerInfo, name:String): Future[Boolean] = {
    httpRequest(Delete(SearchIndexUri(server, name))).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem deleting the search index because the http request contained invalid data.")
        case NoContent  => successful(true)
        case other      => throw new BucketOperationFailed(s"There was a problem deleting the search index '$other' error: ${response.entity.asString}.")
      }
    }
  }

  def getSearchIndex(server: RiakServerInfo, name:String): Future[RiakSearchIndex] = {
    httpRequest(Get(SearchIndexUri(server, name))).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem getting the search index because the http request contained invalid data.")
        case OK         => parseSearchIndex(response.entity)
        case other      => throw new OperationFailed(s"There was a problem retreiving the search index '$other', error: ${response.entity.asString}.")
      }
    }
  }

  def getSearchIndexList(server: RiakServerInfo): Future[List[RiakSearchIndex]] = {
    httpRequest(Get(ListSearchIndexUri(server))).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem getting the search index because the http request contained invalid data.")
        case OK         => parseSearchIndexList(response.entity)
        case other      => throw new OperationFailed(s"There was a problem creating the search index '$other'.")
      }
    }
  }

  def getSearchSchema(server: RiakServerInfo, name:String): Future[scala.xml.Elem] = {
    httpRequest(Get(SearchSchema(server, name))).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem getting the search schema because the http request contained invalid data.")
        case OK         => successful(scala.xml.XML.loadString(response.entity.data.asString))
        case other      => throw new OperationFailed(s"There was a problem obtaining the search schema '$other'.")
      }
    }
  }

  def createSearchSchema(server: RiakServerInfo, name:String, content:scala.xml.Elem): Future[Boolean] = {
    httpRequest(Put(SearchSchema(server, name), HttpEntity(MediaTypes.`application/xml`, content.mkString))).flatMap { response =>
      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem creating the search schema because the http request contained invalid data.")
        case NoContent  => successful(true)
        case other      => throw new OperationFailed(s"There was a problem creating the search schema '$other'.")
      }
    }
  }

  def getKeys(server:RiakServerInfo, bucket:String, bucketType:String):Future[List[String]] = {
    log.warn("Do not use this in production, as it requires traversing through all keys stored in a cluster")
    httpRequest(Get(KeysUri(server, bucket, bucketType))).flatMap { response =>
      import spray.json._
      import DefaultJsonProtocol._

      val keyList = response.entity.data.asString.parseJson.asJsObject.fields("keys").convertTo[List[String]]

      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem retrieving the list because the http request contained invalid data.")
        case OK  => successful(keyList)
        case other      => throw new OperationFailed(s"There was a problem retrieving the key list '$other'.")
      }
    }
  }

  def getKeysStream(server:RiakServerInfo, bucket:String, bucketType:String):RiakStream[List[String]] = {
    log.warn("Do not use this in production, as it requires traversing through all keys stored in a cluster")
    import scala.concurrent.duration._

    val request = Get(KeysStreamUri(server, bucket, bucketType))

    val streamer = RiakStream[List[String]]()

    val enumerator = Concurrent.unicast[RiakChunkedMessage[List[String]]](onStart = channel => {
      actor(
        new Act {
          system.actorOf(Props(classOf[RiakKeysStreamActor], request)) ! "start"
          become {
            case data:RiakChunkedMessageFinish[List[String]] =>
              channel.push(data)
              context.stop(self)
            case data:RiakChunkedMessageResponse[List[String]] =>
              channel.push(data)

            case ReceiveTimeout =>
              streamer.timeoutError()
              context.stop(self)
          }
        }
      )
    })

    enumerator |>> Iteratee.foreach{
      case chunk:RiakChunkedMessageFinish[List[String]] =>
        streamer.finish(chunk)
      case chunk:RiakChunkedMessageResponse[List[String]] =>
        streamer.setChunk(chunk)
    }

    streamer
  }

  def getBuckets(server:RiakServerInfo, client:RiakClient, bucketType:String):Future[List[RiakBucket]] = {
    log.warn("Do not use this in production, as it requires traversing through all keys stored in a cluster")
    httpRequest(Get(BucketsUri(server, bucketType))).flatMap { response =>
      import spray.json._
      import DefaultJsonProtocol._

      val bucketList = response.entity.data.asString.parseJson
        .asJsObject.fields("buckets")
        .convertTo[List[String]]
        .map(x => client.bucket(x))

      response.status match {
        case BadRequest => throw new ParametersInvalid(s"There was a problem retrieving the list because the http request contained invalid data.")
        case OK  => successful(bucketList)
        case other      => throw new OperationFailed(s"There was a problem retrieving the key list '$other'.")
      }
    }
  }

  // ==========================================================================
  // Map Reduce
  // ==========================================================================

  def mapReduce[R: RootJsonReader](server: RiakServerInfo, input: RiakMapReduce.Input, phases: Seq[(RiakMapReduce.QueryPhase.Value, RiakMapReduce.QueryPhase)]): Future[R] = {
    import com.scalapenos.riak.serialization.RiakMapReduceSerialization._
    import spray.json._
    import spray.httpx.unmarshalling._
    import spray.httpx.SprayJsonSupport._

    val entity = JsObject(
      "inputs" → input.toJson,
      "query"  → JsArray(
        (phases map {
          case (op, spec) ⇒ JsObject(op.toString.toLowerCase → spec.toJson)
        }).toList
      )
    )

    httpRequest(Post(mapReduceUrl(server), entity)) map { response ⇒
      response.status match {
        case OK ⇒ response.entity.as[R] match {
          case Right(result) ⇒ result
          case Left(error)   ⇒ throw new MapReduceOperationFailed(error.toString)
        }
        case other ⇒ throw new MapReduceOperationFailed(response.entity.asString)
      }
    }
  }

  // ==========================================================================
  // Search utils
  // ==========================================================================

  private def parseSearchIndex(entity: HttpEntity): Future[RiakSearchIndex] = {
    import spray.httpx.unmarshalling._
    entity.as[RiakSearchIndex] match {
      case Right(value) => successful(value)
      case Left(error) => failed(throw new OperationFailed(s"There was a problem serializing the result"))
    }
  }

  private def parseSearchIndexList(entity:HttpEntity):Future[List[RiakSearchIndex]] = {
    import spray.httpx.unmarshalling._
    entity.as[List[RiakSearchIndex]] match {
      case Right(value) => successful(value)
      case Left(error) => failed(throw new OperationFailed(s"There was a problem serializing the result"))
    }

  }

  // ==========================================================================
  // Solr Building
  // ==========================================================================

  private def toRiakSearch(response: HttpResponse, server: RiakServerInfo):RiakSearchResult = {

    val entity:HttpEntity = response.entity
    val headers:List[HttpHeader] = response.headers

    entity.toOption.map { body =>
      import spray.json._

      val responseHeader:JsObject =
        body.asString.parseJson.asJsObject.fields.get("responseHeader").get.asJsObject
      val response:JsObject =
        body.asString.parseJson.asJsObject.fields.get("response").get.asJsObject

      val responseObject = response.convertTo[RiakSearchResponse]

      //TODO: Fix double quote in string to avoid using replace

      RiakSearchResult(
        response=responseObject,
        responseHeader=responseHeader.convertTo[RiakSearchResponseHeader],
        contentType=body.contentType,
        data=body.asString)
    }.get
  }

  // ==========================================================================
  // Request building
  // ==========================================================================

  private lazy val clientId = java.util.UUID.randomUUID().toString
  private val clientIdHeader = if (settings.AddClientIdHeader) Some(RawHeader(`X-Riak-ClientId`, clientId)) else None

  private def httpRequest:SendReceive = {
    addOptionalHeader(clientIdHeader) ~>
      addHeader("Accept", "*/*, multipart/mixed") ~>
      sendReceive
  }

  private def createStoreHttpRequest(value: RiakValue) = {
    val vclockHeader = value.vclock.toOption.map(vclock => RawHeader(`X-Riak-Vclock`, vclock))
    val indexHeaders = value.indexes.map(toIndexHeader(_)).toList

    addOptionalHeader(vclockHeader) ~>
      addHeaders(indexHeaders) ~>
      httpRequest
  }

  // ==========================================================================
  // Response => RiakValue
  // ==========================================================================

  private def toRiakValue(response: HttpResponse): Option[RiakValue] = toRiakValue(response.entity, response.headers)
  private def toRiakValue(entity: HttpEntity, headers: List[HttpHeader]): Option[RiakValue] = {
    entity.toOption.flatMap { body =>
      val vClockOption       = headers.find(_.is(`X-Riak-Vclock`.toLowerCase)).map(_.value)
      val eTagOption         = headers.find(_.is("etag")).map(_.value)
      val lastModifiedOption = headers.find(_.is("last-modified")).map(h => dateTimeFromLastModified(h.asInstanceOf[`Last-Modified`]))
      val indexes            = toRiakIndexes(headers)

      for (vClock <- vClockOption; eTag <- eTagOption; lastModified <- lastModifiedOption)
      yield RiakValue(body.asString, body.contentType, vClock, eTag, fromSprayDateTime(lastModified), indexes)
    }
  }

  private def dateTimeFromLastModified(lm: `Last-Modified`): DateTime = toSprayDateTime(fromSprayDateTime(lm.date))
  private def lastModifiedFromDateTime(dateTime: DateTime): `Last-Modified` = `Last-Modified`(dateTime)

  // ==========================================================================
  // Index result fetching
  // ==========================================================================

  private def fetchWithKeysReturnedByIndexLookup(server: RiakServerInfo, bucket: String, bucketType:String, response: HttpResponse, resolver: RiakConflictsResolver): Future[List[RiakValue]] = {
    response.entity.toOption.map { body =>
      import spray.json._

      val keys = body.asString.parseJson.convertTo[RiakIndexQueryResponse].keys

      traverse(keys)(fetch(server, bucket, bucketType, _, resolver)).map(_.flatten)
    }.getOrElse(successful(Nil))
  }

  // ==========================================================================
  // Conflict Resolution
  // ==========================================================================

  private def resolveConflict(server: RiakServerInfo, bucket: String, bucketType:String, key: String, response: HttpResponse, resolver: RiakConflictsResolver): Future[RiakValue] = {
    import spray.http._
    import spray.httpx.unmarshalling._

    val vclockHeader = response.headers.find(_.is(`X-Riak-Vclock`.toLowerCase)).toList

    response.entity.as[MultipartContent](MultipartContentUnmarshaller) match {
      case Left(error) => throw new ConflictResolutionFailed(error.toString)
      case Right(multipartContent) => {
        // TODO: make ignoring deleted values optional

        val values = multipartContent.parts.filterNot(part => part.headers.exists(_.lowercaseName == `X-Riak-Deleted`.toLowerCase))
                                           .flatMap(part => toRiakValue(part.entity, vclockHeader ++ part.headers))
                                           .toSet

        // Store the resolved value back to Riak and return the resulting RiakValue
        val ConflictResolution(result, writeBack) = resolver.resolve(values)
        if (writeBack) {
          storeAndFetch(server, bucket, bucketType, key, result, resolver)
        } else {
          successful(result)
        }
      }
    }
  }

  //Fix for avoiding errors when using unmarshal MultipartContent
  //TODO: Remove this when Spray fix the problem
  val mimeParsingConfig = {
    val config = new MIMEConfig
    config.setMemoryThreshold(-1) // use only in-memory parsing
    config
  }

  implicit val MultipartContentUnmarshaller = multipartContentUnmarshaller(`UTF-8`)
  def multipartContentUnmarshaller(defaultCharset: HttpCharset): Unmarshaller[MultipartContent] =
    multipartPartsUnmarshaller[MultipartContent](`multipart/*`, defaultCharset, MultipartContent(_))

  implicit val MultipartByteRangesUnmarshaller = multipartByteRangesUnmarshaller(`UTF-8`)
  def multipartByteRangesUnmarshaller(defaultCharset: HttpCharset): Unmarshaller[MultipartByteRanges] =
    multipartPartsUnmarshaller[MultipartByteRanges](`multipart/byteranges`, defaultCharset, MultipartByteRanges(_))

  def multipartPartsUnmarshaller[T <: MultipartParts](mediaRange: MediaRange,
                                                      defaultCharset: HttpCharset,
                                                      create: Seq[BodyPart] ⇒ T): Unmarshaller[T] =

    Unmarshaller[T](mediaRange) {
      case HttpEntity.NonEmpty(contentType, data) =>
        contentType.mediaType.parameters.get("boundary") match {
          case None | Some("") =>
            throw new Error("Content-Type with a multipart media type must have a non-empty 'boundary' parameter")
          case Some(boundary) =>
            val mimeMsg = new MIMEMessage(new ByteArrayInputStream(data.toByteArray), boundary, mimeParsingConfig)
            create(convertMimeMessage(mimeMsg, defaultCharset))
        }
      case HttpEntity.Empty ⇒ create(Nil)
    }

  def convertMimeMessage(mimeMsg: MIMEMessage, defaultCharset: HttpCharset): Seq[BodyPart] = {
    mimeMsg.getAttachments.asScala.map { part =>
      val rawHeaders: List[HttpHeader] =
        part.getAllHeaders.asScala.map(h => RawHeader(h.getName, h.getValue))(collection.breakOut)
      HttpParser.parseHeaders(rawHeaders) match {
        case (Nil, headers) =>
          val contentType = headers.mapFind { case `Content-Type`(t) => Some(t); case _ ⇒ None }
            .getOrElse(ContentType(`text/plain`, defaultCharset)) // RFC 2046 section 5.1
          val outputStream = new ByteArrayOutputStream
            FileUtils.copyAll(part.readOnce(), outputStream)
            BodyPart(HttpEntity(contentType, outputStream.toByteArray), headers)

        case (errors, headers) =>
          val contentType = headers.mapFind { case `Content-Type`(t) => Some(t); case _ ⇒ None }
            .getOrElse(ContentType(`text/plain`, defaultCharset)) // RFC 2046 section 5.1
          val outputStream = new ByteArrayOutputStream
            FileUtils.copyAll(part.readOnce(), outputStream)
            BodyPart(HttpEntity(contentType, outputStream.toByteArray), headers)
      }
    }(collection.breakOut)
  }
}
