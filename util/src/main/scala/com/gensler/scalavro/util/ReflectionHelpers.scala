package com.gensler.scalavro.util

import scala.collection.immutable.ListMap
import scala.reflect.api.{ Universe, Mirror, TypeCreator }
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
  * Companion object for [[ReflectionHelpers]]
  */
object ReflectionHelpers extends ReflectionHelpers

trait ReflectionHelpers extends Logging {

  protected[scalavro] val classLoaderMirror = runtimeMirror(getClass.getClassLoader)

  /**
    * Returns a sequence of Strings, each of which names a value of the
    * supplied enumeration type.
    */
  protected[scalavro] def symbolsOf[E <: Enumeration: TypeTag]: Seq[String] = {
    val valueType = typeOf[E#Value]

    val isValueType = (sym: Symbol) => {
      !sym.isMethod && !sym.isType &&
        sym.typeSignature.baseType(valueType.typeSymbol) =:= valueType
    }

    typeOf[E].members.collect {
      case sym: Symbol if isValueType(sym) => sym.name.toString.trim
    }.toSeq.reverse
  }

  /**
    * Returns a type tag for the parent `scala.Enumeration` of the supplied
    * enumeration value type.
    */
  protected[scalavro] def enumForValue[V <: Enumeration#Value: TypeTag]: TypeTag[_ <: Enumeration] = {
    val TypeRef(enclosing, _, _) = typeOf[V]
    tagForType(enclosing).asInstanceOf[TypeTag[_ <: Enumeration]]
  }

  private lazy val reflections = {
    import org.reflections.Reflections
    import org.reflections.util.{ ConfigurationBuilder, FilterBuilder }
    import org.reflections.scanners.SubTypesScanner

    val classFilter = new FilterBuilder
    classFilter.excludePackage("java")
    classFilter.excludePackage("javax")
    classFilter.excludePackage("scala")
    classFilter.excludePackage("ch.qos.logback")
    classFilter.excludePackage("com.gensler.scalavro.io")
    classFilter.excludePackage("com.gensler.scalavro.types")
    classFilter.excludePackage("com.google.common")
    classFilter.excludePackage("com.google.guava")
    classFilter.excludePackage("com.google.code.findbugs")
    classFilter.excludePackage("org.dom4j")
    classFilter.excludePackage("org.parboiled")
    classFilter.excludePackage("org.scalatest")
    classFilter.excludePackage("com.thoughtworks.paranamer")
    classFilter.excludePackage("org.tukaani.xz")
    classFilter.excludePackage("org.apache.avro")
    classFilter.excludePackage("org.apache.commons")
    classFilter.excludePackage("org.codehaus.jackson")
    classFilter.excludePackage("org.javassist")
    classFilter.excludePackage("org.w3c.dom")
    classFilter.excludePackage("org.xerial.snappy")
    classFilter.excludePackage("org.xml.sax")
    classFilter.excludePackage("org.scalatest")
    classFilter.excludePackage("org.slf4j")
    classFilter.excludePackage("org.reflections")
    classFilter.excludePackage("sbt")
    classFilter.excludePackage("spray.json")

    val urls = getClass.getClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs

    val configBuilder = new ConfigurationBuilder
    configBuilder setUrls (urls: _*)
    configBuilder.useParallelExecutor() // scan using # available processors
    configBuilder filterInputsBy classFilter
    configBuilder setScanners new SubTypesScanner(false)

    configBuilder.build
  }

  /**
    * Returns `true` iff the supplied class symbol corresponds to a
    * serializable type.
    */
  protected[scalavro] def classSymbolIsTypeable(sym: ClassSymbol): Boolean = {

    val symType = sym.selfType

    sym.isPrimitive ||
      sym.isAbstractClass || sym.isTrait ||
      (sym.isCaseClass && sym.typeParams.isEmpty) ||
      symType <:< typeOf[Set[_]] ||
      symType <:< typeOf[Map[String, _]] ||
      symType <:< typeOf[Seq[_]] ||
      symType.baseClasses.head.owner == typeOf[Enumeration].typeSymbol ||
      classLoaderMirror.runtimeClass(symType.typeSymbol.asClass).isEnum ||
      symType <:< typeOf[FixedData] ||
      symType <:< typeOf[Either[_, _]] ||
      symType <:< typeOf[Option[_]] ||
      symType <:< typeOf[Union.not[_]] ||
      symType <:< typeOf[Union[_]]
  }

  /**
    * Returns a TypeTag for each currently loaded avro-typeable subtype of
    * the supplied type.
    */
  protected[scalavro] def typeableSubTypesOf[T: TypeTag]: Seq[TypeTag[_]] = {
    import scala.collection.JavaConversions.asScalaSet
    import java.lang.reflect.Modifier

    val tType = typeOf[T]
    val tSym = typeOf[T].typeSymbol

    if (!tSym.isClass) Seq()

    else if (tSym.asClass.isSealed) {
      tSym.asClass.knownDirectSubclasses.collect {
        case sym: Symbol if (
          sym.isClass &&
          !sym.asClass.isAbstractType &&
          classSymbolIsTypeable(sym.asClass)
        ) => tagForType(sym.asClass.selfType)
      }.toSeq
    }

    else {
      // filter out abstract classes
      val subClassSymbols = asScalaSet(
        reflections.getSubTypesOf(classLoaderMirror.runtimeClass(typeOf[T]))
      ).collect {
          case clazz: Class[_] if !Modifier.isAbstract(clazz.getModifiers) =>
            classLoaderMirror classSymbol clazz
        }

      // filter class symbols to include only avro-typable classes
      subClassSymbols.collect {
        case sym: ClassSymbol if classSymbolIsTypeable(sym) => tagForType(sym.selfType)
      }.toSeq
    }
  }

  /**
    * Returns a map from formal parameter names to type tags, containing one
    * mapping for each constructor argument.  The resulting map (a ListMap)
    * preserves the order of the primary constructor's parameter list.
    */
  protected[scalavro] def caseClassParamsOf[T: TypeTag]: ListMap[String, TypeTag[_]] = {
    val tpe = typeOf[T]
    val constructorSymbol = tpe.declaration(nme.CONSTRUCTOR)
    val defaultConstructor =
      if (constructorSymbol.isMethod) constructorSymbol.asMethod
      else {
        val ctors = constructorSymbol.asTerm.alternatives
        ctors.map { _.asMethod }.find { _.isPrimaryConstructor }.get
      }

    ListMap[String, TypeTag[_]]() ++ defaultConstructor.paramss.reduceLeft(_ ++ _).map {
      sym => sym.name.toString -> tagForType(tpe.member(sym.name).asMethod.returnType)
    }
  }

  /**
    * Returns Some(MethodMirror) for the public construcor of the supplied
    * class type that takes the supplied argument type as its only parameter.
    *
    * Returns None if no suitable public single-argument constructor can
    * be found for the supplied type.
    *
    * @tparam T the type of the class to inspect for a suitable single-argument
    *           constructor
    * @tparam A the type of the constructor's formal parameter
    */
  protected[scalavro] def singleArgumentConstructor[T: TypeTag, A: TypeTag]: Option[MethodMirror] = {

    val classType = typeOf[T]
    val targetArgType = typeOf[A]
    val constructorSymbol = classType.declaration(nme.CONSTRUCTOR)

    def isPublicAndMatchesArgument(methodSymbol: MethodSymbol): Boolean =
      methodSymbol.isPublic && {
        methodSymbol.asMethod.paramss match {
          case List(List(argSym)) => argSym.typeSignatureIn(classType) =:= targetArgType
          case _                  => false
        }
      }

    val singleArgumentConstructor: Option[MethodSymbol] =
      if (constructorSymbol.isMethod && isPublicAndMatchesArgument(constructorSymbol.asMethod)) {
        Some(constructorSymbol.asMethod)
      }
      else {
        val ctors = constructorSymbol.asTerm.alternatives
        ctors.map { _.asMethod }.find { isPublicAndMatchesArgument }
      }

    singleArgumentConstructor.collect {
      case constructorSymbol: MethodSymbol if classType.typeSymbol.isClass => {
        val classMirror = classLoaderMirror reflectClass classType.typeSymbol.asClass
        classMirror reflectConstructor constructorSymbol
      }
    }
  }

  /**
    * Returns a TypeTag in the current runtime universe for the supplied type.
    */
  protected[scalavro] def tagForType(tpe: Type): TypeTag[_] = TypeTag(
    classLoaderMirror,
    new TypeCreator {
      def apply[U <: Universe with Singleton](m: Mirror[U]) = tpe.asInstanceOf[U#Type]
    }
  )

  /**
    * Returns Some(methodMirror) for the public varargs apply method of the
    * supplied type's companion object, if one exists.  Returns None otherwise.
    */
  protected[scalavro] def companionVarargsApply[T: TypeTag]: Option[MethodMirror] = {

    def isPublicAndVarargs(methodSymbol: MethodSymbol): Boolean = {
      methodSymbol.isPublic && methodSymbol.isVarargs
    }

    val typeSymbol = typeOf[T].typeSymbol
    if (!typeSymbol.isClass) None // supplied type is not a class
    else {
      val classSymbol = typeSymbol.asClass
      if (!classSymbol.companionSymbol.isModule) None // supplied class type has no companion
      else {
        val moduleSymbol = classSymbol.companionSymbol.asModule
        val companionInstance = classLoaderMirror.reflectModule(moduleSymbol).instance
        val companionInstanceMirror = classLoaderMirror reflect companionInstance
        val companionClassType = moduleSymbol.moduleClass.asClass.asType.toType

        val applySymbol: Option[MethodSymbol] = {
          val symbol = companionClassType.member("apply": TermName)
          if (symbol.isMethod && isPublicAndVarargs(symbol.asMethod)) Some(symbol.asMethod)
          else {
            val choices = symbol.asTerm.alternatives
            choices.map(_.asMethod).find(isPublicAndVarargs)
          }
        }

        applySymbol.map { symbol => companionInstanceMirror reflectMethod symbol }
      }
    }
  }

  /**
    * Provides access to named members of instances of the supplied type `P`.
    *
    * @tparam P         the type of the product instance in question
    * @tparam T         the expected type of the member value
    * @param membername the name of the member value to extract
    */
  class ProductElementExtractor[P: TypeTag, T: TypeTag](memberName: String) {
    val memberField = typeOf[P].declaration(memberName: TermName).asTerm.accessed.asTerm
    implicit val ct = ClassTag[P](classLoaderMirror runtimeClass typeOf[P])

    /**
      * Attempts to fetch the value from the supplied product instance.
      *
      * @param product    an instance of some product type, P
      */
    def extractFrom(product: P): T = {
      val instanceMirror = classLoaderMirror reflect product
      val fieldValue = instanceMirror reflectField memberField
      fieldValue.get.asInstanceOf[T]
    }
  }

  /**
    * Encapsulates functionality to reflectively invoke the constructor
    * for a given case class type `T`.
    *
    * @tparam T the type of the case class this factory builds
    */
  class CaseClassFactory[T: TypeTag] {

    val tpe = typeOf[T]
    val classSymbol = tpe.typeSymbol.asClass

    if (!(tpe <:< typeOf[Product] && classSymbol.isCaseClass))
      throw new IllegalArgumentException(
        "CaseClassFactory only applies to case classes!"
      )

    val classMirror = classLoaderMirror reflectClass classSymbol

    val constructorSymbol = tpe.declaration(nme.CONSTRUCTOR)

    val defaultConstructor =
      if (constructorSymbol.isMethod) constructorSymbol.asMethod
      else {
        val ctors = constructorSymbol.asTerm.alternatives
        ctors.map { _.asMethod }.find { _.isPrimaryConstructor }.get
      }

    val constructorMethod = classMirror reflectConstructor defaultConstructor

    /**
      * Attempts to create a new instance of the specified type by calling the
      * constructor method with the supplied arguments.
      *
      * @tparam T   the type of object to construct, which must be a case class
      * @param args the arguments to supply to the constructor method
      */
    def buildWith(args: Seq[_]): T = constructorMethod(args: _*).asInstanceOf[T]

  }

}
