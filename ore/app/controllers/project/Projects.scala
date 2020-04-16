package controllers.project

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Base64

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import play.api.i18n.MessagesApi
import play.api.libs.Files.TemporaryFile
import play.api.mvc._

import controllers.sugar.Requests.AuthRequest
import controllers.{OreBaseController, OreControllerComponents}
import form.OreForms
import form.project.{DiscussionReplyForm, FlagForm}
import models.viewhelper.ScopedOrganizationData
import ore.StatTracker
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.UserTable
import ore.db.{DbRef, Model}
import ore.markdown.MarkdownRenderer
import ore.member.MembershipDossier
import ore.models.{Job, JobInfo}
import ore.models.api.ProjectApiKey
import ore.models.organization.Organization
import ore.models.project._
import ore.models.project.factory.ProjectFactory
import ore.models.user._
import ore.models.user.role.ProjectUserRole
import ore.permission._
import ore.util.OreMDC
import ore.util.StringUtils._
import _root_.util.syntax._
import util.{FileIO, UserActionLogger}
import views.html.{projects => views}

import cats.instances.option._
import cats.syntax.all._
import com.typesafe.scalalogging
import zio.blocking.Blocking
import zio.interop.catz._
import zio.{IO, Task, UIO, ZIO}

/**
  * Controller for handling Project related actions.
  */
class Projects(stats: StatTracker[UIO], forms: OreForms)(
    implicit oreComponents: OreControllerComponents,
    messagesApi: MessagesApi,
    renderer: MarkdownRenderer
) extends OreBaseController {

  private val self = controllers.project.routes.Projects

  private val Logger    = scalalogging.Logger("Projects")
  private val MDCLogger = scalalogging.Logger.takingImplicit[OreMDC](Logger.underlying)

  private def SettingsEditAction(author: String, slug: String) =
    AuthedProjectAction(author, slug, requireUnlock = true)
      .andThen(ProjectPermissionAction(Permission.EditProjectSettings))

  private def MemberEditAction(author: String, slug: String) =
    AuthedProjectAction(author, slug, requireUnlock = true)
      .andThen(ProjectPermissionAction(Permission.ManageProjectMembers))

  /**
    * Displays the Project with the specified author and name.
    *
    * @param author Owner of project
    * @param slug   Project slug
    * @return View of project
    */
  def show(author: String, slug: String, vuePagE: String): Action[AnyContent] =
    ProjectAction(author, slug).asyncF { implicit request =>
      stats.projectViewed(UIO.succeed(Ok(views.view(request.data))))
    }

  /**
    * Posts a new discussion reply to the forums.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       View of discussion with new post
    */
  def postDiscussionReply(author: String, slug: String): Action[DiscussionReplyForm] =
    AuthedProjectAction(author, slug).asyncF(
      parse.form(forms.ProjectReply, onErrors = FormError(self.show(author, slug, "")))
    ) { implicit request =>
      val formData = request.body
      if (request.project.topicId.isEmpty)
        IO.fail(BadRequest)
      else {
        // Do forum post and display errors to user if any
        for {
          poster <- {
            ZIO
              .fromOption(formData.poster)
              .flatMap { posterName =>
                users.requestPermission(request.user, posterName, Permission.PostAsOrganization).toZIO
              }
              .asError(request.user)
              .either
              .map(_.merge)
          }
          topicId <- ZIO.fromOption(request.project.topicId).asError(BadRequest)
          _       <- service.insert(Job.PostDiscourseReply.newJob(topicId, poster.name, formData.content).toJob)
        } yield Redirect(self.show(author, slug, ""))
      }
    }

  /**
    * Shows either a customly uploaded icon for a [[ore.models.project.Project]]
    * or the owner's avatar if there is none.
    *
    * @param author Project owner
    * @param slug Project slug
    * @return Project icon
    */
  def showIcon(author: String, slug: String): Action[AnyContent] = Action.asyncF {
    projects
      .withSlug(author, slug)
      .get
      .asError(NotFound)
      .flatMap(project => project.obj.iconUrlOrPath.map(_.fold(Redirect(_), showImage)))
  }

  private def showImage(path: Path) = {
    val lastModified     = Files.getLastModifiedTime(path).toString.getBytes("UTF-8")
    val lastModifiedHash = MessageDigest.getInstance("MD5").digest(lastModified)
    val hashString       = Base64.getEncoder.encodeToString(lastModifiedHash)
    Ok.sendPath(path)
      .withHeaders(ETAG -> s""""$hashString"""", CACHE_CONTROL -> s"max-age=${1.hour.toSeconds}")
  }

  /**
    * Submits a flag on the specified project for further review.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       View of project
    */
  def flag(author: String, slug: String): Action[FlagForm] =
    AuthedProjectAction(author, slug).asyncF(
      parse.form(forms.ProjectFlag, onErrors = FormErrorLocalized(ShowProject(author, slug)))
    ) { implicit request =>
      val user     = request.user
      val project  = request.project
      val formData = request.body

      user.hasUnresolvedFlagFor(project, ModelView.now(Flag)).flatMap {
        // One flag per project, per user at a time
        case true => IO.fail(BadRequest("Already submitted flag"))
        case false =>
          project
            .flagFor(user, formData.reason, formData.comment)
            .productR(
              UserActionLogger.log(
                request.request,
                LoggedActionType.ProjectFlagged,
                project.id,
                s"Flagged by ${user.name}",
                s"Not flagged by ${user.name}"
              )(LoggedActionProject.apply)
            )
            .as(Redirect(self.show(author, slug, "")).flashing("reported" -> "true"))
      }
    }

  /**
    * Sets whether a [[ore.models.user.User]] is watching a project.
    *
    * @param author   Project owner
    * @param slug     Project slug
    * @param watching True if watching
    * @return         Ok
    */
  def setWatching(author: String, slug: String, watching: Boolean): Action[AnyContent] =
    AuthedProjectAction(author, slug).asyncF { implicit request =>
      request.user.setWatching(request.project, watching).as(Ok)
    }

  def showUserGrid(
      author: String,
      slug: String,
      page: Option[Int],
      title: String,
      query: Model[Project] => Query[UserTable, Model[User], Seq],
      call: Int => Call
  ): Action[AnyContent] = ProjectAction(author, slug).asyncF { implicit request =>
    val pageSize = this.config.ore.projects.userGridPageSize
    val pageNum  = math.max(page.getOrElse(1), 1)
    val offset   = (pageNum - 1) * pageSize

    val queryRes = query(request.project).sortBy(_.name).drop(offset).take(pageSize).result
    service.runDBIO(queryRes).map { users =>
      Ok(
        views.userGrid(
          title,
          call,
          request.data,
          request.scoped,
          Model.unwrapNested(users),
          pageNum,
          pageSize
        )
      )
    }
  }

  def showStargazers(author: String, slug: String, page: Option[Int]): Action[AnyContent] =
    showUserGrid(
      author,
      slug,
      page,
      "Stargazers",
      _.stars.allQueryFromChild,
      page => routes.Projects.showStargazers(author, slug, Some(page))
    )

  def showWatchers(author: String, slug: String, page: Option[Int]): Action[AnyContent] =
    showUserGrid(
      author,
      slug,
      page,
      "Watchers",
      _.watchers.allQueryFromParent,
      page => routes.Projects.showWatchers(author, slug, Some(page))
    )

  /**
    * Sets the "starred" status of a Project for the current user.
    *
    * @param author  Project owner
    * @param slug    Project slug
    * @param starred True if should set to starred
    * @return Result code
    */
  def toggleStarred(author: String, slug: String): Action[AnyContent] =
    AuthedProjectAction(author, slug).asyncF { implicit request =>
      if (request.project.ownerId != request.user.id.value)
        request.data.project.toggleStarredBy(request.user).as(Ok)
      else
        IO.fail(BadRequest)
    }

  /**
    * Sets the status of a pending Project invite for the current user.
    *
    * @param id     Invite ID
    * @param status Invite status
    * @return       NotFound if invite doesn't exist, Ok otherwise
    */
  def setInviteStatus(id: DbRef[ProjectUserRole], status: String): Action[AnyContent] = Authenticated.asyncF {
    implicit request =>
      val user = request.user
      user
        .projectRoles(ModelView.now(ProjectUserRole))
        .get(id)
        .toZIOWithError(NotFound)
        .flatMap { role =>
          import MembershipDossier._
          status match {
            case STATUS_DECLINE =>
              role
                .project[Task]
                .orDie
                .flatMap(project => MembershipDossier.projectHasMemberships[Task].removeRole(project)(role.id).orDie)
                .as(Ok)
            case STATUS_ACCEPT   => service.update(role)(_.copy(isAccepted = true)).as(Ok)
            case STATUS_UNACCEPT => service.update(role)(_.copy(isAccepted = false)).as(Ok)
            case _               => IO.fail(BadRequest)
          }
        }
  }

  /**
    * Sets the status of a pending Project invite on behalf of the Organization
    *
    * @param id     Invite ID
    * @param status Invite status
    * @param behalf Behalf User
    * @return       NotFound if invite doesn't exist, Ok otherwise
    */
  def setInviteStatusOnBehalf(id: DbRef[ProjectUserRole], status: String, behalf: String): Action[AnyContent] =
    Authenticated.asyncF { implicit request =>
      val user = request.user
      val res = for {
        orga       <- organizations.withName(behalf).get
        orgaUser   <- users.withName(behalf).toZIO
        role       <- orgaUser.projectRoles(ModelView.now(ProjectUserRole)).get(id).toZIO
        scopedData <- ScopedOrganizationData.of(Some(user), orga)
        _          <- if (scopedData.permissions.has(Permission.ManageProjectMembers)) ZIO.succeed(()) else ZIO.fail(())
        project    <- role.project[Task].orDie
        res <- {
          import MembershipDossier._
          status match {
            case STATUS_DECLINE =>
              MembershipDossier.projectHasMemberships.removeRole(project)(role.id).as(Ok)
            case STATUS_ACCEPT   => service.update(role)(_.copy(isAccepted = true)).as(Ok)
            case STATUS_UNACCEPT => service.update(role)(_.copy(isAccepted = false)).as(Ok)
            case _               => IO.succeed(BadRequest)
          }
        }
      } yield res

      res.asError(NotFound)
    }

  /**
    * Uploads a new icon to be saved for the specified [[ore.models.project.Project]].
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Ok or redirection if no file
    */
  def uploadIcon(author: String, slug: String): Action[MultipartFormData[TemporaryFile]] =
    SettingsEditAction(author, slug)(parse.multipartFormData).asyncF { implicit request =>
      request.body.file("icon") match {
        case None => IO.fail(Redirect(self.show(author, slug, "")).withError("error.noFile"))
        case Some(tmpFile) =>
          val data = request.data
          val dir  = projectFiles.getIconDir(data.project.ownerName, data.project.name)

          import zio.blocking._

          val notExist   = effectBlocking(Files.notExists(dir))
          val createDir  = effectBlocking(Files.createDirectories(dir))
          val deleteFile = (p: Path) => effectBlocking(Files.delete(p))

          val deleteFiles = effectBlocking(Files.list(dir))
            .map(_.iterator().asScala)
            .flatMap(it => ZIO.foreachParN_(config.performance.nioBlockingFibers)(it.to(Iterable))(deleteFile))

          val moveFile = effectBlocking(tmpFile.ref.moveTo(dir.resolve(tmpFile.filename), replace = true))

          //todo data
          val log = UserActionLogger.log(request.request, LoggedActionType.ProjectIconChanged, data.project.id, "", "")(
            LoggedActionProject.apply
          )

          val res = ZIO.whenM(notExist)(createDir) *> deleteFiles *> moveFile *> log.as(Ok)
          res.orDie
      }
    }

  /**
    * Resets the specified Project's icon to the default user avatar.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Ok
    */
  def resetIcon(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug).asyncF {
    implicit request =>
      import zio.blocking._

      val project = request.project
      val deleteOptFile = (op: Option[Path]) =>
        op.fold(IO.succeed(()): ZIO[Blocking, Throwable, Unit])(p => effectBlocking(Files.delete(p)))

      val res = for {
        icon <- projectFiles.getIconPath(project)
        _    <- deleteOptFile(icon)
        //todo data
        _ <- UserActionLogger.log(request.request, LoggedActionType.ProjectIconChanged, project.id, "", "")(
          LoggedActionProject.apply
        )
      } yield Ok
      res.orDie
  }

  /**
    * Removes a [[ProjectMember]] from the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    */
  def removeMember(author: String, slug: String): Action[String] =
    MemberEditAction(author, slug).asyncF(parse.form(forms.ProjectMemberRemove)) { implicit request =>
      users
        .withName(request.body)
        .toZIOWithError(BadRequest)
        .flatMap { user =>
          val project = request.data.project
          MembershipDossier
            .projectHasMemberships[Task]
            .removeMember(project)(user.id)
            .orDie
            .zipRight(
              UserActionLogger.log(
                request.request,
                LoggedActionType.ProjectMemberRemoved,
                project.id,
                s"'${user.name}' is not a member of ${project.ownerName}/${project.name}",
                s"'${user.name}' is a member of ${project.ownerName}/${project.name}"
              )(LoggedActionProject.apply)
            )
            .as(Redirect(self.show(author, slug, "")))
        }
    }

  /**
    * Sets the visible state of the specified Project.
    *
    * @param author     Project owner
    * @param slug       Project slug
    * @param visibility Project visibility
    * @return         Ok
    */
  def setVisible(author: String, slug: String, visibility: Int): Action[AnyContent] = {
    AuthedProjectAction(author, slug, requireUnlock = true)
      .andThen(ProjectPermissionAction(Permission.Reviewer))
      .asyncF { implicit request =>
        val newVisibility = Visibility.withValue(visibility)

        val addForumJob = service.insert(Job.UpdateDiscourseProjectTopic.newJob(request.project.id).toJob).unit

        val forumVisbility =
          if (Visibility.isPublic(newVisibility) != Visibility.isPublic(request.project.visibility)) {
            addForumJob
          } else IO.unit

        val projectVisibility = if (newVisibility.showModal) {
          val comment = this.forms.NeedsChanges.bindFromRequest.get.trim
          request.project.setVisibility(newVisibility, comment, request.user.id)
        } else {
          request.project.setVisibility(newVisibility, "", request.user.id)
        }

        val log = UserActionLogger.log(
          request.request,
          LoggedActionType.ProjectVisibilityChange,
          request.project.id,
          newVisibility.nameKey,
          Visibility.NeedsChanges.nameKey
        )(LoggedActionProject.apply)

        (forumVisbility, projectVisibility).parTupled
          .productR(log)
          .as(Ok)
      }
  }

  /**
    * Set a project that needed changes to the approval state
    * @param author   Project owner
    * @param slug     Project slug
    * @return         Redirect home
    */
  def sendForApproval(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug).asyncF {
    implicit request =>
      val effects = if (request.data.visibility == Visibility.NeedsChanges) {
        val visibility = request.project.setVisibility(Visibility.NeedsApproval, "", request.user.id)
        val log = UserActionLogger.log(
          request.request,
          LoggedActionType.ProjectVisibilityChange,
          request.project.id,
          Visibility.NeedsApproval.nameKey,
          Visibility.NeedsChanges.nameKey
        )(LoggedActionProject.apply)

        visibility *> log.unit
      } else IO.unit
      effects.as(Redirect(self.show(request.project.ownerName, request.project.slug, "")))
  }

  /**
    * Irreversibly deletes the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Home page
    */
  def delete(author: String, slug: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(Permission.HardDeleteProject)).asyncF { implicit request =>
      getProject(author, slug).flatMap { project =>
        hardDeleteProject(project)
          .as(Redirect(ShowHome).withSuccess(request.messages.apply("project.deleted", project.name)))
      }
    }
  }

  private def hardDeleteProject[A](project: Model[Project])(implicit request: AuthRequest[A]): UIO[Unit] = {
    projects.delete(project).unit <* UserActionLogger.logOption(
      request,
      LoggedActionType.ProjectVisibilityChange,
      None,
      "deleted",
      project.visibility.nameKey
    )(LoggedActionProject.apply)
  }

  /**
    * Soft deletes the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Home page
    */
  def softDelete(author: String, slug: String): Action[String] =
    AuthedProjectAction(author, slug, requireUnlock = true)
      .andThen(ProjectPermissionAction(Permission.DeleteProject))
      .asyncF(parse.form(forms.NeedsChanges)) { implicit request =>
        val oldProject = request.project
        val comment    = request.body.trim

        val ret = if (oldProject.visibility == Visibility.New) {
          hardDeleteProject(oldProject)(request.request)
        } else {
          val oreVisibility = oldProject.setVisibility(Visibility.SoftDelete, comment, request.user.id)

          val forumVisibility = service.insert(Job.UpdateDiscourseProjectTopic.newJob(oldProject.id).toJob)
          val log = UserActionLogger.log(
            request.request,
            LoggedActionType.ProjectVisibilityChange,
            oldProject.id,
            Visibility.SoftDelete.nameKey,
            oldProject.visibility.nameKey
          )(LoggedActionProject.apply)

          (oreVisibility, forumVisibility).parTupled
            .zipRight(log)
            .unit
        }

        ret.as(Redirect(ShowHome).withSuccess(request.messages.apply("project.deleted", oldProject.name)))
      }

  /**
    * Show the flags that have been made on this project
    *
    * @param author Project owner
    * @param slug   Project slug
    */
  def showFlags(author: String, slug: String): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.ModNotesAndFlags)).andThen(ProjectAction(author, slug)) {
      implicit request => Ok(views.admin.flags(request.data))
    }

  /**
    * Show the notes that have been made on this project
    *
    * @param author Project owner
    * @param slug   Project slug
    */
  def showNotes(author: String, slug: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.ModNotesAndFlags)).asyncF { implicit request =>
      getProject(author, slug).flatMap { project =>
        import cats.instances.vector._
        project.decodeNotes.toVector.parTraverse(note => ModelView.now(User).get(note.user).value.tupleLeft(note)).map {
          notes => Ok(views.admin.notes(project, Model.unwrapNested(notes)))
        }
      }
    }
  }

  def addMessage(author: String, slug: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction[AuthRequest](Permission.ModNotesAndFlags))
      .asyncF(parse.form(forms.NoteDescription)) { implicit request =>
        getProject(author, slug)
          .flatMap(_.addNote(Note(request.body.trim, request.user.id)))
          .as(Ok("Review"))
      }
  }
}
