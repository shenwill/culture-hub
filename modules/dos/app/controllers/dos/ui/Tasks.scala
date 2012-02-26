/*
 * Copyright 2011 Delving B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers.dos.ui

import models.dos.{TaskType, TaskState, Task}
import extensions.Extensions
import TaskState._
import org.bson.types.ObjectId
import play.api.mvc._
import play.api.Play
import play.api.Logger
import play.api.Play.current
import eu.delving.templates.scala.GroovyTemplates

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object Tasks extends Controller with Extensions with GroovyTemplates {

  def add(path: String, taskType: String) = Action(parse.tolerantFormUrlEncoded) {
    implicit request =>

      val tt = TaskType.valueOf(taskType)
      if (tt.isEmpty) {
        val msg = "Invalid task type " + taskType
        Logger("DoS").error(msg)
        InternalServerError(msg)
      } else {
        val taskParams: Map[String, Seq[String]] = request.body
        val task = Task(node = getNode, path = path, taskType = tt.get, params = taskParams.map(e => (e._1, e._2.head)).toMap)
        Logger("DOS").info("Adding new task to queue: " + task.toString)
        Task.insert(task) match {
          case None => InternalServerError("Could not create da task")
          case Some(taskId) => Json(task.copy(_id = taskId))
        }

      }

  }

  def cancel(id: ObjectId) = Action {
    implicit request =>
      val task = Task.findOneByID(id)
      if (task.isEmpty)
        NotFound("Could not find task with id " + id)
      else {
        Task.cancel(task.get)
        Ok
      }
  }

  def list(what: String) = Action {
    implicit request =>
      val tasks = TaskState.valueOf(what) match {
        case Some(state) if (state == QUEUED || state == RUNNING || state == FINISHED || state == CANCELLED) => Some(Task.list(state))
        case None => None
      }
      if (tasks == None)
        InternalServerError("Invalid task state " + what)
      else
        Json(Map("tasks" -> tasks.get))
  }

  def listAll() = Action {
    implicit request =>
      Json(Map("running" -> Task.list(RUNNING), "queued" -> Task.list(QUEUED), "finished" -> Task.list(FINISHED)))
  }

  def status(id: ObjectId) = Action {
    implicit request =>
      val task = Task.findOneByID(id)
      if (task.isEmpty) NotFound("Could not find task with id " + id)
      else
        Json(
          Map(
            "totalItems" -> task.get.totalItems,
            "processedItems" -> task.get.processedItems,
            "percentage" -> ((task.get.processedItems.toDouble / task.get.totalItems) * 100).round
          ))
  }

  private def getNode = Play.configuration.getString("cultureHub.nodeName").getOrElse("defaultDosNode")

}