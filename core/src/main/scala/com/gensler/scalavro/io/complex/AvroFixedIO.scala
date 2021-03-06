package com.gensler.scalavro.io.complex

import com.gensler.scalavro.io.AvroTypeIO
import com.gensler.scalavro.types.complex.AvroFixed
import com.gensler.scalavro.error.{ AvroSerializationException, AvroDeserializationException }
import com.gensler.scalavro.util.FixedData
import com.gensler.scalavro.util.ReflectionHelpers

import org.apache.avro.Schema
import org.apache.avro.Schema.Parser
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericFixed
import org.apache.avro.io.{ BinaryEncoder, BinaryDecoder }

import scala.collection.immutable
import scala.collection.mutable
import scala.util.{ Try, Success, Failure }
import scala.reflect.runtime.universe.TypeTag

import java.io.{ InputStream, OutputStream }

case class AvroFixedIO[T <: FixedData: TypeTag](avroType: AvroFixed[T]) extends AvroTypeIO[T] {

  protected lazy val avroSchema: Schema = (new Parser) parse avroType.selfContainedSchema().toString

  protected lazy val bytesConstructorMirror =
    ReflectionHelpers.singleArgumentConstructor[T, immutable.Seq[Byte]].get

  protected[scalavro] def write[F <: T: TypeTag](
    obj: F,
    encoder: BinaryEncoder,
    references: mutable.Map[Any, Long],
    topLevel: Boolean): Unit = {

    encoder writeFixed obj.bytes.toArray
    encoder.flush
  }

  protected[scalavro] def read(
    decoder: BinaryDecoder,
    references: mutable.ArrayBuffer[Any],
    topLevel: Boolean) = {

    val buffer = Array.ofDim[Byte](avroType.size)
    decoder.readFixed(buffer)
    bytesConstructorMirror.apply(buffer.toIndexedSeq).asInstanceOf[T]
  }

}