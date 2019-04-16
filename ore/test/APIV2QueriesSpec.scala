import play.api.Configuration

import db.impl.query.APIV2Queries
import ore.OreConfig
import ore.permission.Permission
import ore.project.Category

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class APIV2QueriesSpec extends DbSpec {

  implicit val config: OreConfig = new OreConfig(
    Configuration.load(getClass.getClassLoader, System.getProperties, Map.empty, allowMissingApplicationConf = false)
  )

  test("FindApiKey") {
    check(APIV2Queries.findApiKey("Foo", "Bar"))
  }

  test("CreateApiKey") {
    check(APIV2Queries.createApiKey("Foo", 0L, "Bar", "Baz", Permission.None))
  }

  test("DeleteApiKey") {
    check(APIV2Queries.deleteApiKey("Foo", 0L))
  }

  /* Uses views
  test("ProjectQuery") {
    check(
      APIV2Queries.projectQuery(
        Some("foo"),
        List(Category.AdminTools),
        List("Foo:Bar"),
        Some("Foo"),
        Some("Foo"),
        canSeeHidden = false,
        Some(0L),
        ProjectSortingStrategy.MostDownloads,
        orderWithRelevance = true,
        20L,
        0L
      )
    )
  }
   */

  test("ProjectCountQuery") {
    check(
      APIV2Queries.projectCountQuery(
        Some("foo"),
        List(Category.AdminTools),
        List("Foo:Bar"),
        Some("Foo"),
        Some("Foo"),
        canSeeHidden = false,
        Some(0L),
      )
    )
  }

  test("ProjectMembers") {
    check(APIV2Queries.projectMembers("Foo", 20L, 0L))
  }

  test("VersionCountQuery") {
    check(APIV2Queries.versionCountQuery("Foo", List("Foo:Bar")))
  }

  test("UserQuery") {
    check(APIV2Queries.userQuery("Foo"))
  }
}
