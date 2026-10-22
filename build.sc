import mill._
import scalalib._

val workspace = os.Path(sys.env("MILL_WORKSPACE_ROOT"))

/** Reusable Compact CHI requester protocol environment extracted from AnserL2. */
object compactChi extends SbtModule {
  override def scalaVersion = T("2.13.15")
  override def millSourcePath = workspace
  override def ivyDeps = Agg(ivy"org.chipsalliance::chisel:6.7.0")
  override def scalacPluginIvyDeps = T { Agg(ivy"org.chipsalliance:::chisel-plugin:6.7.0") }
  override def scalacOptions = T { Seq("-deprecation", "-feature", "-language:reflectiveCalls") }

  object test extends SbtTests with TestModule.ScalaTest {
    override def millSourcePath = workspace
    override def ivyDeps = Agg(
      ivy"org.chipsalliance::chisel:6.7.0",
      ivy"edu.berkeley.cs::chiseltest:6.0.0"
    )
    override def scalacPluginIvyDeps = T { Agg(ivy"org.chipsalliance:::chisel-plugin:6.7.0") }
    override def scalacOptions = T { Seq("-language:reflectiveCalls") }
  }
}
