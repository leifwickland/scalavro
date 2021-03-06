package com.gensler.scalavro.io.complex

import com.gensler.scalavro.io.AvroTypeIO
import com.gensler.scalavro.io.primitive.{ AvroLongIO, AvroStringIO }
import com.gensler.scalavro.types.complex.AvroMap
import com.gensler.scalavro.error.{ AvroSerializationException, AvroDeserializationException }
import com.gensler.scalavro.util.ReflectionHelpers

import org.apache.avro.Schema
import org.apache.avro.Schema.Parser
import org.apache.avro.generic.{ GenericData, GenericDatumWriter }
import org.apache.avro.io.{ BinaryEncoder, BinaryDecoder }
import org.apache.avro.util.Utf8

import scala.collection.mutable
import scala.util.{ Try, Success, Failure }
import scala.reflect.runtime.universe.TypeTag

import java.io.{ InputStream, OutputStream }

case class AvroMapIO[T, M <: Map[String, T]](avroType: AvroMap[T, M]) extends AvroTypeIO[M]()(avroType.originalTypeTag) {

  implicit def itemTypeTag = avroType.itemType.tag
  implicit def originalTypeTag = avroType.originalTypeTag

  val originalTypeVarargsApply = ReflectionHelpers.companionVarargsApply[M] match {
    case Some(methodMirror) => methodMirror
    case None => throw new IllegalArgumentException(
      "Map subclasses must have a companion object with a public varargs " +
        "apply method, but no such method was found for type [%s].".format(avroType.originalTypeTag.tpe)
    )
  }

  protected[scalavro] def write[M <: Map[String, T]: TypeTag](
    map: M,
    encoder: BinaryEncoder,
    references: mutable.Map[Any, Long],
    topLevel: Boolean): Unit = {

    try {
      encoder.writeMapStart
      encoder.setItemCount(map.size)
      for ((key, value) <- map) {
        encoder.startItem
        encoder writeString key
        avroType.itemType.io.write(value, encoder, references, false)
      }
      encoder.writeMapEnd
      encoder.flush
    }
    catch {
      case cause: Throwable =>
        throw new AvroSerializationException(map, cause)
    }
  }

  protected[scalavro] def read(
    decoder: BinaryDecoder,
    references: mutable.ArrayBuffer[Any],
    topLevel: Boolean) = {

    val items = new scala.collection.mutable.ArrayBuffer[(String, T)]

    def readBlock(): Long = {
      val numItems = (AvroLongIO read decoder)
      val absNumItems = math abs numItems
      if (numItems < 0L) { val bytesInBlock = AvroLongIO read decoder }
      (0L until absNumItems) foreach { _ =>
        val key = AvroStringIO read decoder
        val value = avroType.itemType.io.read(decoder, references, false)
        items += key -> value
      }
      absNumItems
    }

    var itemsRead = readBlock()
    while (itemsRead != 0L) { itemsRead = readBlock() }
    originalTypeVarargsApply(items).asInstanceOf[M] // a Seq of tuples is passed to varargs MethodMirror.apply
  }

}