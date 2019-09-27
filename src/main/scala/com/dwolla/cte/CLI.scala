package com.dwolla.cte

import cats._
import cats.data._
import cats.effect._
import cats.implicits._
import com.dwolla.aws.ecs._
import com.dwolla.cte.ConsulToEcs.ConsulAndEcsAlgebra
import com.monovore.decline._
import com.monovore.decline.effect._
import helm.http4s.Http4sConsulClient
import org.http4s._
import org.http4s.client.blaze.BlazeClientBuilder
import software.amazon.awssdk.regions.Region

object CLI extends CommandIOApp("ConsulToEcs", "a utility to find ECS tasks that are failing in Consul", version = "1.0.0") {
  override def main: Opts[IO[ExitCode]] =
    CommandLineOptions().map(MainApp[IO].run(_).map(_ => ExitCode.Success))
}

object MainApp {
  private def freeInterpreterResource[F[_] : ConcurrentEffect : Parallel](consulUri: Uri): Resource[F, ConsulAndEcsAlgebra ~> F] =
    for {
      blocker <- Blocker[F]
      http4sClient <- BlazeClientBuilder[F](blocker.blockingContext).resource
      ecsAlg <- EcsAlg.resource[F](Region.US_WEST_2)
    } yield new Http4sConsulClient(consulUri, http4sClient) or EcsOp.interpreter(ecsAlg)

  def apply[F[_] : ConcurrentEffect : Parallel]: Kleisli[F, CommandLineOptions, Unit] =
    Kleisli { opts =>
      freeInterpreterResource(opts.env.consulUri)
        .use(ConsulToEcs(opts).foldMap[F])
        .flatMap(nodes => Sync[F].delay(println(s"${opts.serviceName} nodes in ${opts.healthStatus} status: $nodes")))
    }
}
