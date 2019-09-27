package com.dwolla.cte

import cats.implicits._
import com.monovore.decline.Opts
import helm.HealthStatus

case class CommandLineOptions(env: Environment, serviceName: ConsulServiceName, healthStatus: HealthStatus, stopTasks: ShouldFailingTasksBeStopped)

object CommandLineOptions {
  private val statusAndStopTasks: Opts[(HealthStatus, ShouldFailingTasksBeStopped)] = (
    Opts.option[HealthStatus]("health-status", "Consul health check status", "s").withDefault(HealthStatus.Critical),
    Opts.flag("stop-tasks", "Stop critical tasks").orFalse.ifA(Opts(StopFailingTasks), Opts(LeaveTasksRunning))
    )
    .tupled
    .mapValidated {
      case x@(HealthStatus.Critical, StopFailingTasks) => x.valid
      case x@(_, LeaveTasksRunning) => x.valid
      case (_, StopFailingTasks) => "Only failing tasks can be stopped by this tool".invalidNel
    }

  def apply(): Opts[CommandLineOptions] = (
    Opts.option[Environment]("environment", "Dwolla environment (DevInt, Uat, or Prod)", "e"),
    Opts.option[ConsulServiceName]("service-name", "Consul Service Name", "n"),
    statusAndStopTasks
    )
    .mapN(CommandLineOptions.apply)

  private def apply(env: Environment, serviceName: ConsulServiceName, tuple: (HealthStatus, ShouldFailingTasksBeStopped)): CommandLineOptions =
    CommandLineOptions(env, serviceName, tuple._1, tuple._2)
}
