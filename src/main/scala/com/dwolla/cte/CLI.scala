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
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.blaze.BlazeClientBuilder
import software.amazon.awssdk.regions.Region

object CLI extends CommandIOApp("ConsulToEcs", "a utility to find ECS tasks that are failing in Consul", version = "1.0.0") {
  implicit val log: Logger[IO] = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    CommandLineOptions().map(MainApp[IO].run(_).map(_ => ExitCode.Success))
}

object MainApp {
  def apply[F[_] : ConcurrentEffect : Parallel : Logger]: Kleisli[F, CommandLineOptions, Unit] =
    optionsToTaskList[F] >>= logOutput[F]

  private def optionsToTaskList[F[_] : ConcurrentEffect : Parallel]: Kleisli[F, CommandLineOptions, List[Task]] =
    interpreterResource.tapWithMapF(_.use((runProgram[F] _).tupled))

  private def interpreterResource[F[_] : ConcurrentEffect : Parallel]: Kleisli[Resource[F, *], CommandLineOptions, ConsulAndEcsAlgebra ~> F] =
    Kleisli { opts: CommandLineOptions =>
      for {
        blocker <- Blocker[F]
        http4sClient <- BlazeClientBuilder[F](blocker.blockingContext).resource
        ecsAlg <- EcsAlg.resource[F](Region.US_WEST_2)
      } yield new Http4sConsulClient(opts.env.consulUri, http4sClient) or EcsOp.interpreter(ecsAlg)
    }

  private def runProgram[F[_] : Monad](opts: CommandLineOptions, interpreter: ConsulAndEcsAlgebra ~> F): F[List[Task]] =
    ConsulToEcs(opts).foldMap[F](interpreter)

  private def logOutput[F[_] : Logger](tasks: List[Task]) =
    Kleisli { opts: CommandLineOptions =>
      Logger[F].info(s"${opts.serviceName} nodes in ${opts.healthStatus} status: $tasks")
    }

  private implicit class KleisliTapWithMapF[F[_], A, B](private val k: Kleisli[F, A, B]) extends AnyVal {
    /**
     * Yield computed B combined with input value to passed function,
     * which can potentially change the context of the arrow
     */
    def tapWithMapF[G[_], C](f: F[(A, B)] => G[C])(implicit F: Functor[F]): Kleisli[G, A, C] =
      k.tapWith((a: A, b: B) => (a, b)).mapF(f)
  }
}
