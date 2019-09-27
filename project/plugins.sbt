addSbtPlugin("com.dwijnand" % "sbt-travisci" % "1.2.0")

resolvers += Resolver.bintrayIvyRepo("dwolla", "sbt-plugins")
addSbtPlugin("com.dwolla.sbt" % "sbt-dwolla-base" % "1.4.0")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.11")
