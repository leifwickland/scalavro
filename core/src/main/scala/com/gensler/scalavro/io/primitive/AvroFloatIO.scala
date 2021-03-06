package com.gensler.scalavro.io.primitive

import com.gensler.scalavro.io.AvroTypeIO
import com.gensler.scalavro.types.primitive.AvroFloat
import com.gensler.scalavro.error.{ AvroSerializationException, AvroDeserializationException }

import org.apache.avro.io.{ BinaryEncoder, BinaryDecoder }

import scala.collection.mutable
import scala.util.{ Try, Success, Failure }
import scala.reflect.runtime.universe.TypeTag

object AvroFloatIO extends AvroFloatIO

trait AvroFloatIO extends AvroPrimitiveTypeIO[Float] {

  val avroType = AvroFloat

  protected[scalavro] def write(
    value: Float,
    encoder: BinaryEncoder): Unit = {

    encoder writeFloat value
    encoder.flush
  }

  def read(decoder: BinaryDecoder) = decoder.readFloat

}
