package com.scalapenos

import spray.http.ContentType
import com.github.nscala_time.time.Imports._


package object riak {

  // ============================================================================
  // Conflict Resolution
  // ============================================================================

  trait ConflictResolver {
    def resolve(values: Set[RiakValue]): RiakValue
  }

  implicit def func2resolver(f: Set[RiakValue] => RiakValue): ConflictResolver = new ConflictResolver {
    def resolve(values: Set[RiakValue]) = f(values)
  }


  // ============================================================================
  // Value Classes
  // ============================================================================

  implicit class Vclock(val value: String) extends AnyVal {
    def isDefined = !isEmpty
    def isEmpty = value.isEmpty
    def toOption: Option[Vclock] = if (isDefined) Some(this) else None
  }

  object Vclock {
    val NotSpecified = new Vclock("")
  }


  // ============================================================================
  // RiakValue
  // ============================================================================

  // TODO: write a converter/serializer/marshaller RiakValue => T
  // TODO: write a converter/deserializer/unmarshaller T => RiakValue

  final case class RiakValue(
    value: Array[Byte],
    contentType: ContentType,
    vclock: Vclock,
    etag: String,
    lastModified: DateTime
    // links: Seq[RiakLink]
    // meta: Seq[RiakMeta]
  ) {
    import scala.util._
    import converters._

    def asString = new String(value, contentType.charset.nioCharset)

    def as[T: RiakValueReader]: Try[T] = implicitly[RiakValueReader[T]].read(this)

    // TODO: add common manipulation functions
  }

  object RiakValue {
    def apply(value: String): RiakValue = {
      apply(value, ContentType.`text/plain`, Vclock.NotSpecified, "", DateTime.now)
    }

    def apply(value: String, contentType: ContentType, vclock: Vclock, etag: String, lastModified: DateTime): RiakValue = {
      RiakValue(
        value.getBytes(contentType.charset.nioCharset),
        contentType,
        vclock,
        etag,
        lastModified
      )
    }

    import spray.http.HttpBody
    import spray.httpx.marshalling._
    implicit val RiakValueMarshaller: Marshaller[RiakValue] = new Marshaller[RiakValue] {
      def apply(riakValue: RiakValue, ctx: MarshallingContext) {
        ctx.marshalTo(HttpBody(riakValue.contentType, riakValue.value))
      }
    }
  }


  // ============================================================================
  // Exceptions
  // ============================================================================

  case class BucketOperationFailed(cause: String) extends RuntimeException(cause)
  case class ConflictResolutionFailed(cause: String) extends RuntimeException(cause)
  case class ParametersInvalid(cause: String) extends RuntimeException(cause)

}