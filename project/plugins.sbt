logLevel := Level.Warn
evictionWarningOptions in update := EvictionWarningOptions.default
  .withWarnTransitiveEvictions(false)
  .withWarnDirectEvictions(false)
  .withWarnScalaVersionEviction(false)

resolvers += "Typesafe repository".at("https://repo.typesafe.com/typesafe/releases/")

addSbtPlugin("com.typesafe.play" % "sbt-plugin"        % "2.8.1")
addSbtPlugin("com.typesafe.sbt"  % "sbt-digest"        % "1.1.4")
addSbtPlugin("com.typesafe.sbt"  % "sbt-gzip"          % "1.0.2")
addSbtPlugin("ch.epfl.scala"     % "sbt-scalafix"      % "0.9.0")
addSbtPlugin("com.iheart"        %% "sbt-play-swagger" % "0.9.1-PLAY2.8")

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.8.1"
