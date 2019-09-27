# Consul to ECS Lookup Tool

[![Travis](https://img.shields.io/travis/Dwolla/consul-to-ecs.svg?style=flat-square)](https://travis-ci.org/Dwolla/consul-to-ecs)
[![license](https://img.shields.io/github/license/Dwolla/consul-to-ecs.svg?style=flat-square)]()

Scala command line app to find ECS Tasks corresponding to Consul services.

## Usage

Use `--help` to display options:

```
Usage: ConsulToEcs --environment <DevInt | Uat | Prod> --service-name <string> [--health-status <Critical | Passing | Healthy | Unknown | Warning>] [--stop-tasks]

a utility to find ECS tasks that are failing in Consul

Options and flags:
    --help
        Display this help text.
    --version, -v
        Print the version number and exit.
    --environment <DevInt | Uat | Prod>, -e <DevInt | Uat | Prod>
        Dwolla environment (DevInt, Uat, or Prod)
    --service-name <string>, -n <string>
        Consul Service Name
    --health-status <Critical | Passing | Healthy | Unknown | Warning>, -s <Critical | Passing | Healthy | Unknown | Warning>
        Consul health check status
    --stop-tasks
        Stop critical tasks
```

The `--stop-tasks` flag is only compatible with `--health-status Critical`. An error message will be printed if it is combined with any other statuses.
