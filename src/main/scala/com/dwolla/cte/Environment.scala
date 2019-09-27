package com.dwolla.cte

import cats.data._
import cats.implicits._
import com.dwolla.aws.ecs._
import com.monovore.decline._
import org.http4s._

sealed trait Environment {
  val ecsCluster: Cluster
  val consulUri: Uri
}
object Environment {
  implicit val environmentArgumentInstance: Argument[Environment] = new Argument[Environment] {
    override def read(string: String): ValidatedNel[String, Environment] = string.toLowerCase() match {
      case "devint" => DevInt.valid
      case "uat" => Uat.valid
      case "prod" | "production" => Prod.valid
      case _ => """Invalid environment. Choose one of (DevInt, Uat, Prod)""".invalidNel
    }

    override def defaultMetavar: String = Seq("DevInt", "Uat", "Prod").mkString(" | ")
  }
}
case object DevInt extends Environment {
  override val ecsCluster: Cluster = tagCluster("DevInt")
  override val consulUri: Uri = uri"https://consul.us-west-2.devint.dwolla.net"
}
case object Uat extends Environment {
  override val ecsCluster: Cluster = tagCluster("Uat")
  override val consulUri: Uri = uri"https://consul.us-west-2.uat.dwolla.net"
}
case object Prod extends Environment {
  override val ecsCluster: Cluster = tagCluster("Production")
  override val consulUri: Uri = uri"https://consul.us-west-2.prod.dwolla.net"
}
