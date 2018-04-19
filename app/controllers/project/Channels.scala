package controllers.project

import controllers.OreBaseController
import controllers.sugar.Bakery
import db.ModelService
import form.OreForms
import javax.inject.Inject

import ore.permission.EditChannels
import ore.project.factory.ProjectFactory
import ore.{OreConfig, OreEnv}
import play.api.cache.AsyncCacheApi
import play.api.i18n.MessagesApi
import security.spauth.SingleSignOnConsumer
import views.html.projects.{channels => views}
import util.instances.future._
import scala.concurrent.{ExecutionContext, Future}

import util.functional.EitherT
import util.syntax._

/**
  * Controller for handling Channel related actions.
  */
class Channels @Inject()(forms: OreForms,
                         factory: ProjectFactory,
                         implicit override val bakery: Bakery,
                         implicit override val cache: AsyncCacheApi,
                         implicit override val sso: SingleSignOnConsumer,
                         implicit override val messagesApi: MessagesApi,
                         implicit override val env: OreEnv,
                         implicit override val config: OreConfig,
                         implicit override val service: ModelService)(implicit val ec: ExecutionContext)
                         extends OreBaseController {

  private val self = controllers.project.routes.Channels

  private def ChannelEditAction(author: String, slug: String)
  = AuthedProjectAction(author, slug, requireUnlock = true) andThen ProjectPermissionAction(EditChannels)

  /**
    * Displays a view of the specified Project's Channels.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return View of channels
    */
  def showList(author: String, slug: String) = ChannelEditAction(author, slug).async { request =>
    implicit val r = request.request
    for {
      channels <- request.data.project.channels.toSeq
      versionCount <- Future.sequence(channels.map(_.versions.size))
    } yield {
      val listWithVersionCount = channels zip versionCount
      Ok(views.list(request.data, listWithVersionCount))
    }
  }

  /**
    * Creates a submitted channel for the specified Project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Redirect to view of channels
    */
  def create(author: String, slug: String) = ChannelEditAction(author, slug).async { implicit request =>
    this.forms.ChannelEdit.bindFromRequest.fold(
      hasErrors => Future.successful(Redirect(self.showList(author, slug)).withError(hasErrors.errors.head.message)),
      channelData => {
        channelData.addTo(request.data.project).fold(
          error => Redirect(self.showList(author, slug)).withError(error),
          _ => Redirect(self.showList(author, slug))
        )
      }
    )
  }

  /**
    * Submits changes to an existing channel.
    *
    * @param author      Project owner
    * @param slug        Project slug
    * @param channelName Channel name
    * @return View of channels
    */
  def save(author: String, slug: String, channelName: String) = ChannelEditAction(author, slug).async { implicit request =>
    implicit val data = request.data
    this.forms.ChannelEdit.bindFromRequest.fold(
      hasErrors =>
        Future.successful(Redirect(self.showList(author, slug)).withError(hasErrors.errors.head.message)),
      channelData => {
        implicit val p = data.project
        channelData.saveTo(channelName).cata(
          Redirect(self.showList(author, slug)),
          error => Redirect(self.showList(author, slug)).withError(error)
        )
      }
    )
  }

  /**
    * Irreversibly deletes the specified channel and all version associated
    * with it.
    *
    * @param author      Project owner
    * @param slug        Project slug
    * @param channelName Channel name
    * @return View of channels
    */
  def delete(author: String, slug: String, channelName: String) = ChannelEditAction(author, slug).async { implicit request =>
    implicit val data = request.data
    EitherT.right[Status](data.project.channels.all)
      .filterOrElse(_.size == 1, Redirect(self.showList(author, slug)).withError("error.channel.last"))
      .flatMap { channels =>
        EitherT.fromEither[Future](channels.find(c => c.name.equals(channelName)).toRight(NotFound))
          .semiFlatMap { channel =>
            (channel.versions.nonEmpty, Future.sequence(channels.map(_.versions.nonEmpty)).map(l => l.count(_ == true))).parMapN {
              (nonEmpty, channelCount) => (nonEmpty, channelCount, channel)
            }
          }
          .filterOrElse(
            { case (nonEmpty, channelCount, _) => nonEmpty && channelCount == 1},
            Redirect(self.showList(author, slug)).withError("error.channel.lastNonEmpty")
          )
          .map(_._3)
          .filterOrElse(
            channel => {
              val reviewedChannels = channels.filter(!_.isNonReviewed)
              !channel.isNonReviewed && reviewedChannels.size <= 1 && reviewedChannels.contains(channel)
            },
            Redirect(self.showList(author, slug)).withError("error.channel.lastReviewed")
          )
          .map { channel =>
            this.projects.deleteChannel(channel)
            Redirect(self.showList(author, slug))
          }
      }.merge
  }
}
