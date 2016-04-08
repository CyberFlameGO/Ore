package controllers

import javax.inject.Inject

import controllers.routes.{Pages => self}
import play.api.i18n.MessagesApi
import play.api.mvc.Action
import util.Forms
import views.{html => views}

/**
  * Controller for handling Page related actions.
  */
class Pages @Inject()(override val messagesApi: MessagesApi) extends BaseController {

  /**
    * Displays the specified page.
    *
    * @param author   Project owner
    * @param slug     Project slug
    * @param page     Page name
    * @return         View of page
    */
  def show(author: String, slug: String, page: String) = Action { implicit request =>
    withProject(author, slug, project => {
      project.page(page) match {
        case None => NotFound
        case Some(p) => Ok(views.projects.pages.home(project, p))
      }
    }, countView = true)
  }

  /**
    * Displays the documentation page editor for the specified project and page
    * name.
    *
    * @param author   Owner name
    * @param slug     Project slug
    * @param page     Page name
    * @return         Page editor
    */
  def showEditor(author: String, slug: String, page: String) = { withUser(Some(author), user => implicit request =>
    withProject(author, slug, project => {
      Ok(views.projects.pages.edit(project, page, project.getOrCreatePage(page).contents))
    }))
  }

  /**
    * Saves changes made on a documentation page.
    *
    * @param author   Owner name
    * @param slug     Project slug
    * @param page     Page name
    * @return         Project home
    */
  def save(author: String, slug: String, page: String) = { withUser(Some(author), user => implicit request =>
    // TODO: Validate content size and title
    withProject(author, slug, project => {
      val pageForm = Forms.PageEdit.bindFromRequest.get
      project.getOrCreatePage(page).contents = pageForm._2
      Redirect(self.show(author, slug, page))
    }))
  }

  /**
    * Irreversibly deletes the specified Page from the specified Project.
    *
    * @param author   Project owner
    * @param slug     Project slug
    * @param page     Page name
    * @return         Redirect to Project homepage
    */
  def delete(author: String, slug: String, page: String) = withUser(Some(author), user => implicit request => {
    withProject(author, slug, project => {
      project.deletePage(page)
      Redirect(routes.Projects.show(author, slug))
    })
  })

}
