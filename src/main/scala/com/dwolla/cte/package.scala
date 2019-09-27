package com.dwolla

import cats._
import cats.data._
import cats.implicits._
import com.monovore.decline._
import helm.HealthStatus
import helm.HealthStatus._
import shapeless.tag
import shapeless.tag.@@

package object cte {
  type ConsulServiceName = String @@ ConsulServiceNameTag

  val tagConsulServiceName: String => ConsulServiceName = tag[ConsulServiceNameTag][String]

  implicit def taggedArgument[A, B <: String @@ A](implicit eq: String @@ A =:= B): Argument[B] =
    Argument[String].map(tag[A][String])

  implicit val healthStatusArgument: Argument[HealthStatus] =
    new Argument[HealthStatus] {
      override def read(string: String): ValidatedNel[String, HealthStatus] = string.toLowerCase() match {
        case "critical" => Critical.valid
        case "passing" | "healthy" => Passing.valid
        case "unknown" => Unknown.valid
        case "warning" => Warning.valid
        case other => s"$other is not a valid health status".invalidNel
      }

      override def defaultMetavar: String = Seq("Critical", "Passing", "Healthy", "Unknown", "Warning").mkString(" | ")
    }

  implicit def argumentApplicativeInstance: Functor[Argument] = new Functor[Argument] {
    override def map[A, B](fa: Argument[A])(f: A => B): Argument[B] = new Argument[B] {
      override def read(string: String): ValidatedNel[String, B] = fa.read(string).map(f)

      override def defaultMetavar: String = fa.defaultMetavar
    }
  }

}

package cte {
  trait ConsulServiceNameTag
}
