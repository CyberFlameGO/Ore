package ore.models.project.io

import java.nio.file.{Files, Path}

import ore.db.{DbRef, Model}
import ore.models.project.{Asset, PluginInfoParser, Project, Version, VersionPlatform}
import ore.models.user.User
import ore.util.StringUtils
import ore.{OreConfig, OrePlatform}

import cats.effect.Sync

class PluginFileWithData(val path: Path, val user: Model[User], val entries: List[PluginInfoParser.Entry])(
    implicit config: OreConfig
) {

  def delete[F[_]](implicit F: Sync[F]): F[Unit] = F.delay(Files.delete(path))

  /**
    * Returns an MD5 hash of this PluginFile.
    *
    * @return MD5 hash
    */
  lazy val md5: String = StringUtils.md5ToHex(Files.readAllBytes(this.path))

  lazy val fileSize: Long = Files.size(path)

  lazy val fileName: String = path.getFileName.toString

  lazy val dependencyIds: Seq[String]              = entries.flatMap(_.dependencies).map(_.identifier)
  lazy val dependencyVersions: Seq[Option[String]] = entries.flatMap(_.dependencies).map(_.rawVersion)

  lazy val versionName: String = StringUtils.compact(entries.head.version)
  lazy val versionSlug: String = StringUtils.slugify(entries.head.version)

  lazy val (platformWarnings: List[String], versionedPlatforms: List[VersionedPlatform]) =
    OrePlatform.createVersionedPlatforms(dependencyIds, dependencyVersions).run

  def warnings: Seq[String] = platformWarnings

  //TODO: Support multiple platforms here
  def asVersion(
      projectId: DbRef[Project],
      description: Option[String],
      createForumPost: Boolean,
      stability: Version.Stability,
      releaseType: Option[Version.ReleaseType],
      pluginAssetId: DbRef[Asset]
  ): Version = Version(
    name = versionName,
    slug = versionSlug,
    projectId = projectId,
    authorId = Some(user.id),
    description = description,
    createForumPost = createForumPost,
    pluginAssetId = pluginAssetId,
    tags = Version.VersionTags(
      usesMixin = entries.exists(_.mixin),
      stability = stability,
      releaseType = releaseType
    )
  )

  def asAsset(projectId: DbRef[Project]): Asset =
    Asset(
      projectId,
      fileName,
      md5,
      fileSize
    )

  def asPlatforms(versionId: DbRef[Version]): List[VersionPlatform] =
    versionedPlatforms.map(p => VersionPlatform(versionId, p.id, p.version, p.coarseVersion))
}
