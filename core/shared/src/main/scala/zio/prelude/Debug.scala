/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.prelude

import zio.duration.{Duration => ZIODuration}
import zio.{Chunk, NonEmptyChunk}

import java.util.concurrent.TimeUnit
import scala.collection.immutable.ListMap
import scala.concurrent.duration.{Duration => ScalaDuration}
import scala.language.implicitConversions

trait Debug[-A] {
  def debug(a: A): Debug.Repr
}

object Debug extends DebugVersionSpecific {
  type Renderer = Repr => String
  object Renderer {
    val Scala: Renderer = {
      case Repr.Float(v)                                                               => v.toString
      case Repr.Long(v)                                                                => v.toString
      case Repr.Char(v)                                                                => v.toString
      case Repr.String(v)                                                              => v
      case Repr.KeyValue(k, v)                                                         => s"${k.render(Scala)} -> ${v.render(Scala)}"
      case Repr.Object(_, n)                                                           => n
      case Repr.Constructor(_, n, reprs)                                               =>
        s"$n(${reprs.map(kv => kv._2.render(Scala)).mkString(",")})"
      case Repr.VConstructor(_, n, reprs) if List("List", "Vector", "Map").contains(n) =>
        s"$n(${reprs.map(_.render(Scala)).mkString(", ")})"
      case Repr.VConstructor(List("scala"), n, reprs) if n.matches("^Tuple\\d+$")      =>
        s"(${reprs.map(_.render(Scala)).mkString(",")})"
      case Repr.VConstructor(_, n, reprs)                                              => s"$n(${reprs.map(_.render(Scala)).mkString(",")})"
      case any                                                                         => Simple(any)
    }

    val Simple: Renderer = {
      case Repr.Int(v)                                                            => v.toString
      case Repr.Double(v)                                                         => v.toString
      case Repr.Float(v)                                                          => s"${v}f"
      case Repr.Long(v)                                                           => s"${v}L"
      case Repr.Byte(v)                                                           => v.toString
      case Repr.Char(v)                                                           => s"'$v'"
      case Repr.Boolean(v)                                                        => v.toString
      case Repr.Short(v)                                                          => v.toString
      case Repr.String(v)                                                         => s""""$v""""
      case Repr.KeyValue(k, v)                                                    => s"${k.render(Simple)} -> ${v.render(Simple)}"
      case Repr.Object(_, n)                                                      => n
      case Repr.Constructor(_, n, reprs)                                          =>
        s"$n(${reprs.map(kv => s"${kv._1} = ${kv._2.render(Simple)}").mkString(", ")})"
      case Repr.VConstructor(List("scala"), n, reprs) if n.matches("^Tuple\\d+$") =>
        s"(${reprs.map(_.render(Simple)).mkString(", ")})"
      case Repr.VConstructor(_, n, reprs)                                         => s"$n(${reprs.map(_.render(Simple)).mkString(", ")})"
    }

    val Full: Renderer = {
      case Repr.KeyValue(k, v)             => s"key: ${k.render(Full)} -> value: ${v.render(Full)}"
      case Repr.Object(ns, n)              => (ns :+ n).mkString(".")
      case Repr.Constructor(ns, n, reprs)  =>
        (ns :+ s"$n(${reprs.map(kv => s"${kv._1} = ${kv._2.render(Full)}").mkString(", ")})").mkString(".")
      case Repr.VConstructor(ns, n, reprs) =>
        (ns :+ n).mkString(".") + s"(${reprs.map(_.render(Full)).mkString(", ")})"
      case any                             => Simple(any)
    }
  }

  def apply[A](implicit debug: Debug[A]): Debug[A] = debug

  def make[A](f: A => Debug.Repr): Debug[A] = f(_)

  sealed trait Repr { self =>
    def render(renderer: Renderer): String = renderer(self)
    def render: String                     = render(Renderer.Simple)
    override def toString: String          = render // to show a nice view in IDEs, REPL, etc
  }

  object Repr {
    import java.lang.{String => SString}
    import scala.{
      Boolean => SBoolean,
      Byte => SByte,
      Char => SChar,
      Double => SDouble,
      Float => SFloat,
      Int => SInt,
      Long => SLong,
      Short => SShort
    }

    final case class Int(value: SInt)                                                                    extends Repr
    final case class Double(value: SDouble)                                                              extends Repr
    final case class Float(value: SFloat)                                                                extends Repr
    final case class Long(value: SLong)                                                                  extends Repr
    final case class Byte(value: SByte)                                                                  extends Repr
    final case class Char(value: SChar)                                                                  extends Repr
    final case class Boolean(value: SBoolean)                                                            extends Repr
    final case class Short(value: SShort)                                                                extends Repr
    final case class String(value: SString)                                                              extends Repr
    final case class KeyValue(key: Repr, value: Repr)                                                    extends Repr
    final case class Object(namespace: List[SString], name: SString)                                     extends Repr
    final case class Constructor(namespace: List[SString], name: SString, reprs: ListMap[SString, Repr]) extends Repr
    object Constructor {
      def apply(namespace: List[SString], name: SString, repr: (SString, Repr), reprs: (SString, Repr)*): Repr =
        new Constructor(namespace, name, ListMap(repr :: reprs.toList: _*))
    }
    final case class VConstructor(namespace: List[SString], name: SString, reprs: List[Repr]) extends Repr

    implicit def deriveRepr[A](x: A)(implicit A: Debug[A]): Repr = A.debug(x)
  }

  private val nanosToPrettyUnit: Long => (Long, TimeUnit) = {
    val ns_per_us  = 1000L
    val ns_per_ms  = ns_per_us * 1000
    val ns_per_s   = ns_per_ms * 1000
    val ns_per_min = ns_per_s * 60
    val ns_per_h   = ns_per_min * 60
    val ns_per_d   = ns_per_h * 24

    (nanos: Long) =>
      import java.util.concurrent.TimeUnit._
      if (nanos % ns_per_d == 0) (nanos / ns_per_d, DAYS)
      else if (nanos % ns_per_h == 0) (nanos / ns_per_h, HOURS)
      else if (nanos % ns_per_min == 0) (nanos / ns_per_min, MINUTES)
      else if (nanos % ns_per_s == 0) (nanos / ns_per_s, SECONDS)
      else if (nanos % ns_per_ms == 0) (nanos / ns_per_ms, MILLISECONDS)
      else if (nanos % ns_per_us == 0) (nanos / ns_per_us, MICROSECONDS)
      else (nanos, NANOSECONDS)
  }

  implicit val NothingDebug: Debug[Nothing] = n => n
  implicit val UnitDebug: Debug[Unit]       = _ => Repr.Object("scala" :: Nil, "()")
  implicit val IntDebug: Debug[Int]         = Repr.Int(_)
  implicit val DoubleDebug: Debug[Double]   = Repr.Double(_)
  implicit val FloatDebug: Debug[Float]     = Repr.Float(_)
  implicit val LongDebug: Debug[Long]       = Repr.Long(_)
  implicit val ByteDebug: Debug[Byte]       = Repr.Byte(_)
  implicit val CharDebug: Debug[Char]       = Repr.Char(_)
  implicit val BooleanDebug: Debug[Boolean] = Repr.Boolean(_)
  implicit val ShortDebug: Debug[Short]     = Repr.Short(_)
  implicit val StringDebug: Debug[String]   = Repr.String(_)

  def keyValueDebug[A: Debug, B: Debug]: Debug[(A, B)] = n => Repr.KeyValue(n._1.debug, n._2.debug)

  /**
   * Derives a `Debug[Array[A]]` given a `Debug[A]`.
   */
  implicit def ArrayDebug[A: Debug]: Debug[Array[A]] =
    array => Repr.VConstructor(List("scala"), "Array", array.map(_.debug).toList)

  /**
   * The `Debug` instance for `BigDecimal`.
   */
  implicit val BigDecimalDebug: Debug[BigDecimal] =
    bigDecimal =>
      Repr.VConstructor(List("scala", "math"), "BigDecimal", List(bigDecimal.toString.debug, bigDecimal.mc.debug))

  /**
   * The `Debug` instance for `BigInt`.
   */
  implicit val BigIntDebug: Debug[BigInt] =
    bigInt => Repr.VConstructor(List("scala", "math"), "BigInt", List(bigInt.toString.debug))

  /**
   * The `Debug` instance for `java.math.MathContext`.
   */
  implicit val MathContextDebug: Debug[java.math.MathContext] =
    mc => Repr.VConstructor(List("java", "math"), "MathContext", List(mc.getPrecision.debug, mc.getRoundingMode.debug))

  /**
   * The `Debug` instance for `java.math.RoundingMode`.
   */
  implicit val RoundingModeDebug: Debug[java.math.RoundingMode] = {
    case java.math.RoundingMode.CEILING     => Repr.Object(List("java", "math"), "RoundingMode.CEILING")
    case java.math.RoundingMode.DOWN        => Repr.Object(List("java", "math"), "RoundingMode.DOWN")
    case java.math.RoundingMode.FLOOR       => Repr.Object(List("java", "math"), "RoundingMode.FLOOR")
    case java.math.RoundingMode.HALF_DOWN   => Repr.Object(List("java", "math"), "RoundingMode.HALF_DOWN")
    case java.math.RoundingMode.HALF_EVEN   => Repr.Object(List("java", "math"), "RoundingMode.HALF_EVEN")
    case java.math.RoundingMode.HALF_UP     => Repr.Object(List("java", "math"), "RoundingMode.HALF_UP")
    case java.math.RoundingMode.UNNECESSARY => Repr.Object(List("java", "math"), "RoundingMode.UNNECESSARY")
    case java.math.RoundingMode.UP          => Repr.Object(List("java", "math"), "RoundingMode.UP")
  }

  implicit def ChunkDebug[A: Debug]: Debug[Chunk[A]] =
    chunk => Repr.VConstructor(List("zio"), "Chunk", chunk.map(_.debug).toList)

  /**
   * Derives a `Debug[F[A]]` given a `Derive[F, Debug]` and a `Debug[A]`.
   */
  implicit def DeriveDebug[F[_], A](implicit derive: Derive[F, Debug], debug: Debug[A]): Debug[F[A]] =
    derive.derive(debug)

  implicit val DurationScalaDebug: Debug[ScalaDuration] = {
    val namespace            = List("scala", "concurrent", "duration")
    val constructor          = "Duration"
    val namespaceConstructor = namespace ++ List(constructor)

    {
      case ScalaDuration.Zero      => Repr.Object(namespaceConstructor, "Zero")
      case ScalaDuration.Inf       => Repr.Object(namespaceConstructor, "Inf")
      case ScalaDuration.MinusInf  => Repr.Object(namespaceConstructor, "MinusInf")
      case ScalaDuration.Undefined => Repr.Object(namespaceConstructor, "Undefined")
      case d                       =>
        val (length, unit) = nanosToPrettyUnit(d.toNanos)
        Repr.Constructor(
          namespace,
          constructor,
          ("length", Repr.Long(length)),
          ("unit", unit.debug)
        )
    }
  }

  implicit val DurationZIODebug: Debug[ZIODuration] = {
    val namespace            = List("zio", "duration")
    val constructor          = "Duration"
    val namespaceConstructor = namespace ++ List(constructor)

    {
      case ZIODuration.Zero     => Repr.Object(namespaceConstructor, "Zero")
      case ZIODuration.Infinity => Repr.Object(namespaceConstructor, "Infinity")
      case d                    =>
        val (amount, unit) = nanosToPrettyUnit(d.toNanos)
        Repr.Constructor(
          namespace,
          constructor,
          ("amount", Repr.Long(amount)),
          ("unit", unit.debug)
        )
    }
  }

  implicit def EitherDebug[E: Debug, A: Debug]: Debug[Either[E, A]] = {
    case Left(e)  => Repr.VConstructor(List("scala"), "Left", List(e.debug))
    case Right(a) => Repr.VConstructor(List("scala"), "Right", List(a.debug))
  }

  implicit def NonEmptyChunkDebug[A: Debug]: Debug[NonEmptyChunk[A]] =
    nonEmptyChunk => Repr.VConstructor(List("zio"), "NonEmptyChunk", nonEmptyChunk.map(_.debug).toList)

  implicit def OptionDebug[A: Debug]: Debug[Option[A]] = {
    case None    => Repr.Object(List("scala"), "None")
    case Some(a) => Repr.VConstructor(List("scala"), "Some", List(a.debug))
  }

  implicit def ListDebug[A: Debug]: Debug[List[A]] =
    list => Repr.VConstructor(List("scala"), "List", list.map(_.debug))

  implicit def VectorDebug[A: Debug]: Debug[Vector[A]] =
    vector => Repr.VConstructor(List("scala"), "Vector", vector.map(_.debug).toList)

  implicit def MapDebug[K: Debug, V: Debug]: Debug[Map[K, V]] =
    map => Repr.VConstructor(List("scala"), "Map", map.map(_.debug(keyValueDebug)).toList)

  implicit val TimeUnitDebug: Debug[TimeUnit] = tu =>
    Repr.Object(
      List("java", "util", "concurrent", "TimeUnit"),
      tu match {
        case TimeUnit.NANOSECONDS  => "NANOSECONDS"
        case TimeUnit.MICROSECONDS => "MICROSECONDS"
        case TimeUnit.MILLISECONDS => "MILLISECONDS"
        case TimeUnit.SECONDS      => "SECONDS"
        case TimeUnit.MINUTES      => "SECONDS"
        case TimeUnit.HOURS        => "HOURS"
        case TimeUnit.DAYS         => "DAYS"
      }
    )

  implicit def Tuple2Debug[A: Debug, B: Debug]: Debug[(A, B)] =
    tup2 => Repr.VConstructor(List("scala"), "Tuple2", List(tup2._1.debug, tup2._2.debug))

  implicit def Tuple3Debug[A: Debug, B: Debug, C: Debug]: Debug[(A, B, C)] =
    tuple => Repr.VConstructor(List("scala"), "Tuple3", List(tuple._1.debug, tuple._2.debug, tuple._3.debug))

  implicit def Tuple4Debug[A: Debug, B: Debug, C: Debug, D: Debug]: Debug[(A, B, C, D)] =
    tuple =>
      Repr.VConstructor(List("scala"), "Tuple4", List(tuple._1.debug, tuple._2.debug, tuple._3.debug, tuple._4.debug))

  implicit def Tuple5Debug[A: Debug, B: Debug, C: Debug, D: Debug, E: Debug]: Debug[(A, B, C, D, E)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple5",
        List(tuple._1.debug, tuple._2.debug, tuple._3.debug, tuple._4.debug, tuple._5.debug)
      )

  implicit def Tuple6Debug[A: Debug, B: Debug, C: Debug, D: Debug, E: Debug, F: Debug]: Debug[(A, B, C, D, E, F)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple6",
        List(tuple._1.debug, tuple._2.debug, tuple._3.debug, tuple._4.debug, tuple._5.debug, tuple._6.debug)
      )

  implicit def Tuple7Debug[A: Debug, B: Debug, C: Debug, D: Debug, E: Debug, F: Debug, G: Debug]
    : Debug[(A, B, C, D, E, F, G)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple7",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug
        )
      )

  implicit def Tuple8Debug[A: Debug, B: Debug, C: Debug, D: Debug, E: Debug, F: Debug, G: Debug, H: Debug]
    : Debug[(A, B, C, D, E, F, G, H)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple8",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug
        )
      )

  implicit def Tuple9Debug[A: Debug, B: Debug, C: Debug, D: Debug, E: Debug, F: Debug, G: Debug, H: Debug, I: Debug]
    : Debug[(A, B, C, D, E, F, G, H, I)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple9",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug
        )
      )

  implicit def Tuple10Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple10",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug
        )
      )

  implicit def Tuple11Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple11",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug
        )
      )

  implicit def Tuple12Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple12",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug
        )
      )

  implicit def Tuple13Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple13",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug
        )
      )

  implicit def Tuple14Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple14",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug
        )
      )

  implicit def Tuple15Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple15",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug
        )
      )

  implicit def Tuple16Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple16",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug
        )
      )

  implicit def Tuple17Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug,
    Q: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple17",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug,
          tuple._17.debug
        )
      )

  implicit def Tuple18Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug,
    Q: Debug,
    R: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple18",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug,
          tuple._17.debug,
          tuple._18.debug
        )
      )

  implicit def Tuple19Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug,
    Q: Debug,
    R: Debug,
    S: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple19",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug,
          tuple._17.debug,
          tuple._18.debug,
          tuple._19.debug
        )
      )

  implicit def Tuple20Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug,
    Q: Debug,
    R: Debug,
    S: Debug,
    T: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple20",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug,
          tuple._17.debug,
          tuple._18.debug,
          tuple._19.debug,
          tuple._20.debug
        )
      )

  implicit def Tuple21Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug,
    Q: Debug,
    R: Debug,
    S: Debug,
    T: Debug,
    U: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple21",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug,
          tuple._17.debug,
          tuple._18.debug,
          tuple._19.debug,
          tuple._20.debug,
          tuple._21.debug
        )
      )

  implicit def Tuple22Debug[
    A: Debug,
    B: Debug,
    C: Debug,
    D: Debug,
    E: Debug,
    F: Debug,
    G: Debug,
    H: Debug,
    I: Debug,
    J: Debug,
    K: Debug,
    L: Debug,
    M: Debug,
    N: Debug,
    O: Debug,
    P: Debug,
    Q: Debug,
    R: Debug,
    S: Debug,
    T: Debug,
    U: Debug,
    V: Debug
  ]: Debug[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
    tuple =>
      Repr.VConstructor(
        List("scala"),
        "Tuple22",
        List(
          tuple._1.debug,
          tuple._2.debug,
          tuple._3.debug,
          tuple._4.debug,
          tuple._5.debug,
          tuple._6.debug,
          tuple._7.debug,
          tuple._8.debug,
          tuple._9.debug,
          tuple._10.debug,
          tuple._11.debug,
          tuple._12.debug,
          tuple._13.debug,
          tuple._14.debug,
          tuple._15.debug,
          tuple._16.debug,
          tuple._17.debug,
          tuple._18.debug,
          tuple._19.debug,
          tuple._20.debug,
          tuple._21.debug,
          tuple._22.debug
        )
      )
}

trait DebugSyntax {
  implicit class DebugOps[A](self: A) {
    def debug(implicit debug: Debug[A]): Debug.Repr = debug.debug(self)
  }

  implicit final class DebugInterpolator(_sc: StringContext) {
    def d(args: Debug.Repr*): String = _sc.s(args.map(_.toString): _*)
  }
}
