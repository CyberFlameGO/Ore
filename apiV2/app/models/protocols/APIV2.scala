package models.protocols

import java.time.OffsetDateTime

import ore.data.project.Category
import ore.models.project.Version.{ReleaseType, Stability}
import ore.models.project.{ReviewState, Visibility}

import enumeratum._
import enumeratum.values._
import io.circe._
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import io.circe.syntax._
import shapeless.Typeable

object APIV2 {

  def optionToResult[A](s: String, opt: String => Option[A], history: List[CursorOp])(
      implicit tpe: Typeable[A]
  ): Either[DecodingFailure, A] =
    opt(s).toRight(DecodingFailure(s"$s is not a valid ${tpe.describe}", history))

  def valueEnumCodec[V, A <: ValueEnumEntry[V]: Typeable](enumObj: ValueEnum[V, A])(name: A => String): Codec[A] =
    Codec.from(
      (c: HCursor) => c.as[String].flatMap(optionToResult(_, s => enumObj.values.find(a => name(a) == s), c.history)),
      (a: A) => name(a).asJson
    )

  def enumCodec[A <: EnumEntry: Typeable](enumObj: Enum[A])(name: A => String): Codec[A] = Codec.from(
    (c: HCursor) => c.as[String].flatMap(optionToResult(_, s => enumObj.values.find(a => name(a) == s), c.history)),
    (a: A) => name(a).asJson
  )

  implicit val visibilityCodec: Codec[Visibility]   = valueEnumCodec(Visibility)(_.nameKey)
  implicit val categoryCodec: Codec[Category]       = valueEnumCodec(Category)(_.apiName)
  implicit val reviewStateCodec: Codec[ReviewState] = valueEnumCodec(ReviewState)(_.apiName)

  //Project
  @SnakeCaseJsonCodec case class Project(
      createdAt: OffsetDateTime,
      pluginId: String,
      name: String,
      namespace: ProjectNamespace,
      promotedVersions: Seq[PromotedVersion],
      stats: ProjectStatsAll,
      category: Category,
      summary: Option[String],
      lastUpdated: OffsetDateTime,
      visibility: Visibility,
      userActions: UserActions,
      settings: ProjectSettings,
      iconUrl: String
  )

  @SnakeCaseJsonCodec case class CompactProject(
      pluginId: String,
      name: String,
      namespace: ProjectNamespace,
      promotedVersions: Seq[PromotedVersion],
      stats: ProjectStatsAll,
      category: Category,
      visibility: Visibility
  )

  @SnakeCaseJsonCodec case class ProjectNamespace(owner: String, slug: String)
  @SnakeCaseJsonCodec case class PromotedVersion(version: String, platforms: Seq[VersionPlatform])
  @SnakeCaseJsonCodec case class ProjectStatsAll(
      views: Long,
      downloads: Long,
      recentViews: Long,
      recentDownloads: Long,
      stars: Long,
      watchers: Long
  )
  @SnakeCaseJsonCodec case class UserActions(starred: Boolean, watching: Boolean)
  @SnakeCaseJsonCodec case class ProjectSettings(
      homepage: Option[String],
      issues: Option[String],
      sources: Option[String],
      support: Option[String],
      license: ProjectLicense,
      forumSync: Boolean
  )
  @SnakeCaseJsonCodec case class ProjectLicense(name: Option[String], url: Option[String])

  //Project member
  @SnakeCaseJsonCodec case class ProjectMember(
      user: String,
      roles: List[Role]
  )

  @SnakeCaseJsonCodec case class Role(
      name: String,
      title: String,
      color: String
  )

  //Version
  @SnakeCaseJsonCodec case class Version(
      createdAt: OffsetDateTime,
      name: String,
      dependencies: List[VersionDependency],
      visibility: Visibility,
      stats: VersionStatsAll,
      fileInfo: FileInfo,
      author: Option[String],
      reviewState: ReviewState,
      tags: VersionTags
  )

  @ConfiguredJsonCodec case class VersionTags(
      mixin: Boolean,
      stability: Stability,
      releaseType: Option[ReleaseType],
      platforms: Seq[VersionPlatform]
  )

  @ConfiguredJsonCodec case class VersionPlatform(
      platform: String,
      platformVersion: Option[String],
      displayPlatformVersion: Option[String],
      minecraftVersion: Option[String]
  )

  @ConfiguredJsonCodec case class VersionDescription(description: String)

  @ConfiguredJsonCodec case class VersionDependency(pluginId: String, version: Option[String])
  @ConfiguredJsonCodec case class VersionStatsAll(downloads: Long)
  @ConfiguredJsonCodec case class FileInfo(name: String, sizeBytes: Long, md5Hash: String)

  //User
  @SnakeCaseJsonCodec case class User(
      createdAt: OffsetDateTime,
      name: String,
      tagline: Option[String],
      joinDate: Option[OffsetDateTime],
      roles: List[Role]
  )

  @SnakeCaseJsonCodec case class ProjectStatsDay(
      downloads: Long,
      views: Long
  )

  @SnakeCaseJsonCodec case class VersionStatsDay(
      downloads: Long
  )

  @ConfiguredJsonCodec case class Page(
      name: String,
      content: String
  )
}
