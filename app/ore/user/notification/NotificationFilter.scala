package ore.user.notification

import scala.collection.immutable
import scala.concurrent.Future

import db.access.ModelAccess
import db.impl.OrePostgresDriver.api._
import models.user.Notification

import enumeratum.values._

sealed abstract class NotificationFilter(
    val value: Int,
    val name: String,
    val emptyMessage: String,
    val title: String,
    val filter: Notification#T => Rep[Boolean]
) extends IntEnumEntry {

  def apply(notifications: ModelAccess[Notification]): Future[Seq[Notification]] =
    notifications.filter(this.filter)
}

/**
  * A collection of ways to filter notifications.
  */
object NotificationFilter extends IntEnum[NotificationFilter] {

  val values: immutable.IndexedSeq[NotificationFilter] = findValues

  case object Unread
      extends NotificationFilter(0, "unread", "notification.empty.unread", "notification.unread", !_.read)
  case object Read extends NotificationFilter(1, "read", "notification.empty.read", "notification.read", _.read)
  case object All  extends NotificationFilter(2, "all", "notification.empty.all", "notification.all", _ => true)
}