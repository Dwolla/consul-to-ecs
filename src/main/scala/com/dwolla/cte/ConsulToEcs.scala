package com.dwolla.cte

import cats._
import cats.data._
import cats.free._
import cats.implicits._
import com.comcast.ip4s.{Ipv4Address, Port}
import com.dwolla.aws.ecs._
import com.dwolla.cte.ConsulToEcs._
import helm._

class ConsulToEcs(implicit EcsOp: EcsOpF[ConsulAndEcsAlgebra],
                  I: InjectK[ConsulOp, ConsulAndEcsAlgebra]
                  ) {
  import EcsOp._

  private def maybeStopTasks(tasks: List[Task]): ShouldFailingTasksBeStopped => Free[ConsulAndEcsAlgebra, Unit] = {
    case StopFailingTasks => stopTasks(tasks)
    case LeaveTasksRunning => ignoreTasks
  }

  private val toTasksByNetworkBinding: List[Task] => Map[NetworkBinding, Task] =
    _.flatMap(t => t.containers.flatMap(_.networkBindings).map(_ -> t).toList).toMap

  def run(opts: CommandLineOptions): Free[ConsulAndEcsAlgebra, List[Task]] = {
    for {
      tasksByNetworkBinding <- describeTasks(opts.env.ecsCluster, None).map(toTasksByNetworkBinding)
      rawQueryResponse <- ConsulOp.healthListNodesForService(opts.serviceName, None, None, None, None, None, None, None).mapK(I.inj)
      QueryResponse(tasks, _, _, _) =
        Nested(rawQueryResponse)
          .filter(_.checks.exists(_.status == opts.healthStatus))
          .mapFilter { node =>
            for {
              ip <- Ipv4Address(node.service.address)
              port <- Port(node.service.port)
              task <- tasksByNetworkBinding.get(NetworkBinding(ip, port))
            } yield task
          }
          .value
      _ <- maybeStopTasks(tasks)(opts.stopTasks)
    } yield tasks
  }
}

object ConsulToEcs {
  type ConsulAndEcsAlgebra[T] = EitherK[ConsulOp, EcsOp, T]

  def apply(opts: CommandLineOptions)
           (implicit EcsOp: EcsOpF[ConsulAndEcsAlgebra],
            I: InjectK[ConsulOp, ConsulAndEcsAlgebra],
           ): Free[ConsulAndEcsAlgebra, List[Task]] =
    new ConsulToEcs().run(opts)

  implicit val queryResponseFunctorInstance: Functor[QueryResponse] = new Functor[QueryResponse] {
    override def map[A, B](fa: QueryResponse[A])(f: A => B): QueryResponse[B] =
      fa.copy(value = f(fa.value))
  }
}
