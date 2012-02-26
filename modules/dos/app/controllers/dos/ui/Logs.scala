package controllers.dos.ui

import models.dos._
import com.mongodb.casbah.Imports._
import play.api.mvc._
import extensions.Extensions
import com.novus.salat.dao.SalatMongoCursor

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object Logs extends Controller with Extensions {

  def list(taskId: ObjectId, lastCount: Option[Int]) = Action {
    implicit request =>
      val cursor: SalatMongoCursor[Log] = Log.find(MongoDBObject("task_id" -> taskId)).sort(MongoDBObject("date" -> 1))
      val (logs, skipped) = if (lastCount != None && lastCount.get > 0) {
        if (cursor.count - lastCount.get > 100) {
          (cursor.skip(cursor.count - 100), true)
        } else {
          (cursor.skip(lastCount.get + 1), false)
        }
      } else {
        if (cursor.count > 100) {
          (cursor.skip(cursor.count - 100), true)
        } else {
          (cursor, false)
        }
      }
      Json(Map("logs" -> logs, "skipped" -> skipped))
  }

  def view(taskId: ObjectId) = Action {
    implicit request => {
      val cursor: SalatMongoCursor[Log] = Log.find(MongoDBObject("task_id" -> taskId)).sort(MongoDBObject("date" -> 1))
      Ok(cursor.map(log => log.date + "\t" + log.level.name.toUpperCase + "\t" + log.node + "\t" + log.message).mkString("\n"))
    }
 }

}