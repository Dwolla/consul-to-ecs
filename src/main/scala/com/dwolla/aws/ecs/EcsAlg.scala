package com.dwolla.aws

import cats._
import cats.effect._
import cats.free._
import cats.implicits._
import com.comcast.ip4s._
import fs2._
import shapeless.tag
import shapeless.tag._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2AsyncClient
import software.amazon.awssdk.services.ec2.model._
import software.amazon.awssdk.services.ecs.EcsAsyncClient
import software.amazon.awssdk.services.ecs.model.{DescribeContainerInstancesRequest, DescribeTasksRequest, ListTasksRequest, ContainerInstance => AwsContainerInstance, Task => AwsTask}
import _root_.io.chrisdavenport.log4cats.Logger

package object ecs {
  type Cluster = String @@ ClusterTag
  type ServiceName = String @@ ServiceNameTag
  type TaskId = String @@ TaskIdTag

  val tagCluster: String => Cluster = tag[ClusterTag][String]
  val tagServiceName: String => ServiceName = tag[ServiceNameTag][String]
  val tagTaskId: String => TaskId = tag[TaskIdTag][String]
}

package ecs {

  import software.amazon.awssdk.services.ecs.model.StopTaskRequest

  trait ClusterTag
  trait ServiceNameTag
  trait TaskIdTag

  case class Task(arn: TaskId,
                  cluster: Cluster,
                  containers: Set[Container])

  case class Container(networkBindings: Set[NetworkBinding])

  case class NetworkBinding(address: Ipv4Address,
                            hostPort: Port,
                           )

  trait EcsAlg[F[_]] {
    def describeTasks(cluster: Cluster, service: Option[ServiceName]): Stream[F, Task]
    def stopTask(cluster: Cluster, taskId: TaskId): F[Unit]

    def stopTask(task: Task): F[Unit] = stopTask(task.cluster, task.arn)
  }

  object EcsAlg {
    private def acquireEcsClient[F[_] : Sync](region: Region): F[EcsAsyncClient] = Sync[F].delay {
      EcsAsyncClient.builder().region(region).build()
    }

    private def acquireEc2Client[F[_] : Sync](region: Region): F[Ec2AsyncClient] = Sync[F].delay {
      Ec2AsyncClient.builder().region(region).build()
    }

    private def shutdownClient[F[_]] = new PartialShutdownClient[F]

    def resource[F[_] : ConcurrentEffect](region: Region): Resource[F, EcsAlg[F]] =
      for {
        ecs <- Resource.make(acquireEcsClient[F](region))(shutdownClient[F](_))
        ec2 <- Resource.make(acquireEc2Client[F](region))(shutdownClient[F](_))
      } yield new EcsAlg[F] {
        import scala.jdk.CollectionConverters._

        override def stopTask(cluster: Cluster, taskId: TaskId): F[Unit] = {
          val req: StopTaskRequest =
            StopTaskRequest.builder()
              .cluster(cluster)
              .task(taskId)
              .reason("Service is unhealthy in Consul")
              .build()
          eval[F](req)(ecs.stopTask)(_.task()).compile.drain
        }

        private def listTasks(cluster: Cluster, service: Option[ServiceName]): Stream[F, TaskId] = {
          val request = service.foldl(ListTasksRequest.builder().cluster(cluster))(_.serviceName(_)).build()
          unfold[F](ecs.listTasksPaginator(request))(_.taskArns()).map(tagTaskId)
        }

        override def describeTasks(cluster: Cluster, service: Option[ServiceName]): Stream[F, Task] =
          listTasks(cluster, service)
            .chunkN(100)
            .map(_.map[String](identity).toVector.asJavaCollection)
            .map(DescribeTasksRequest.builder().cluster(cluster).tasks(_).build())
            .flatMap(eval[F](_)(ecs.describeTasks)(_.tasks().asScala))
            .flatMap(Stream.emits)
            .through(enhanceContainerInstanceInfo(cluster))
            .map { case (t, i) =>
              Task(tagTaskId(t.taskArn()), cluster, t.containers().asScala.map(toContainer(i)).toSet)
            }

        private def toContainer(i: Instance)(c: software.amazon.awssdk.services.ecs.model.Container): Container =
          Container(c.networkBindings().asScala.flatMap(toNetworkBinding(i)).toSet)

        private def toNetworkBinding(i: Instance)(nb: software.amazon.awssdk.services.ecs.model.NetworkBinding): Option[NetworkBinding] =
          for {
            h <- Ipv4Address(i.privateIpAddress())
            hp <- Port(nb.hostPort())
          } yield NetworkBinding(h, hp)

        private def describeContainerInstances(cluster: Cluster, containerInstanceIds: Iterable[String]): Stream[F, AwsContainerInstance] = {
          val req = DescribeContainerInstancesRequest.builder().cluster(cluster).containerInstances(containerInstanceIds.asJavaCollection).build()

          eval[F](req)(ecs.describeContainerInstances)(_.containerInstances().asScala).flatMap(Stream.emits)
        }

        type ->[A, B] = (A, B)

        private def enhanceContainerInstanceInfo(cluster: Cluster): Stream[F, AwsTask] => Stream[F, (AwsTask, Instance)] =
          _.chunkN(100)
            .flatMap { tasks =>
              val tasksByContainerInstanceId: Map[String, List[AwsTask]] = tasks.toList.groupBy(_.containerInstanceArn())
              val ciToTasks: F[List[AwsContainerInstance -> AwsTask]] =
                describeContainerInstances(cluster, tasksByContainerInstanceId.keys)
                  .compile
                  .toList
                  .map { containerInstances =>
                    for {
                      ci <- containerInstances
                      t <- tasksByContainerInstanceId.get(ci.containerInstanceArn()).toList.flatten
                    } yield (ci, t)
                  }

              Stream.evalUnChunk(ciToTasks.map(c => Chunk.seq(c))).through(containerInstancesToEc2Instances)
            }

        private def describeEc2Instances(instanceIds: List[String]): Stream[F, Instance] = {
          val describeInstancesRequest = DescribeInstancesRequest.builder().instanceIds(instanceIds.asJavaCollection).build()
          for {
            reservation <- unfold[F](ec2.describeInstancesPaginator(describeInstancesRequest))(_.reservations)
            instance <- Stream.emits(reservation.instances().asScala)
          } yield instance
        }

        private def containerInstancesToEc2Instances: Stream[F, (AwsContainerInstance, AwsTask)] => Stream[F, (AwsTask, Instance)] =
          _.chunkN(100)
            .flatMap { ciChunk: Chunk[(AwsContainerInstance, AwsTask)] =>
              Stream.evalUnChunk {
                describeEc2Instances(ciChunk.map(_._1.ec2InstanceId()).toList)
                  .compile
                  .toList
                  .map { instanceList =>
                    val containerInstances = ciChunk.toList.sortBy(_._1.ec2InstanceId())
                    val instances = instanceList.groupBy(_.instanceId())

                    Chunk.seq(for {
                      (ci, t) <- containerInstances
                      i <- instances.get(ci.ec2InstanceId()).toList.flatten
                    } yield (t, i))
                  }
              }
            }
      }

    private class PartialShutdownClient[F[_]] {
      def apply[A <: AutoCloseable](a: A)(implicit F: Sync[F]): F[Unit] =
        F.delay(a.close())
    }
  }

  sealed trait EcsOp[A]
  case class DescribeTasks(cluster: Cluster, service: Option[ServiceName]) extends EcsOp[List[Task]]
  case class StopTasks(tasks: List[Task]) extends EcsOp[Unit]
  case object IgnoreTasks extends EcsOp[Unit]

  object EcsOp {
    def interpreter[F[_] : Applicative : Parallel : Logger](ecsAlg: EcsAlg[F])(implicit ev: Stream.Compiler[F, F]): EcsOp ~> F = new (EcsOp ~> F) {
      override def apply[A](fa: EcsOp[A]): F[A] = fa match {
        case DescribeTasks(cluster, service) =>
          ecsAlg.describeTasks(cluster, service).compile.toList
        case StopTasks(tasks: List[Task]) =>
// TODO enable tasks to actually be stopped if requested
//          tasks.parTraverse_(ecsAlg.stopTask)
          tasks.parTraverse_(task => Logger[F].warn(s"would stop $task, but this is a dry-run"))
        case IgnoreTasks =>
          Applicative[F].unit
      }
    }
  }

  class EcsOpF[C[_] : InjectK[EcsOp, *[_]]] {
    def describeTasks(cluster: Cluster, service: Option[ServiceName]): Free[C, List[Task]] = Free.inject[EcsOp, C](DescribeTasks(cluster, service))
    def stopTasks(tasks: List[Task]): Free[C, Unit] = Free.inject[EcsOp, C](StopTasks(tasks))
    def ignoreTasks: Free[C, Unit] = Free.inject[EcsOp, C](IgnoreTasks)
  }

  object EcsOpF {
    implicit def ecsOpFInstance[F[_]](implicit I: InjectK[EcsOp, F]): EcsOpF[F] = new EcsOpF[F]
  }
}
