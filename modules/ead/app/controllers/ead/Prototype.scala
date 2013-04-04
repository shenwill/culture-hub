package controllers.ead

import controllers.DelvingController
import play.api.mvc._
import play.api._
import play.api.Play.current
import scala.xml._
import net.liftweb.json._
import collection.immutable.Stack
import collection.mutable
import collection.mutable.ArrayBuffer

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */
object Prototype extends DelvingController {

  def sampleData = Action {
    implicit request =>

      Play.resourceAsStream("mildred_davenport.xml") map {
        resourceStream =>
          {
            val source = Source.fromInputStream(resourceStream)
            val xml = scala.xml.XML.load(source)
            Ok(util.Json.toJson(xml))
          }
      } getOrElse {
        InternalServerError("Couldn't find test resource")
      }
  }

  def sampleView = Action {
    implicit request =>
      Ok(Template())
  }

  def dynatreeSample = Action {
    implicit request =>

      Play.resourceAsStream("mildred_davenport.xml") map {
        resourceStream =>
          {
            val source = Source.fromInputStream(resourceStream)
            val xml = scala.xml.XML.load(source)
            val json = Xml.toJson(xml)
            val transformed = transformToViewTree(json, request.queryString.getFirst("path"))

            Ok(pretty(net.liftweb.json.render(transformed))).as(JSON)
          }
      } getOrElse {
        InternalServerError("Couldn't find test resource")
      }

  }

  def transformToViewTree(json: JValue, key: Option[String]): JValue = {
    json match {
      case o @ JObject(fields: Seq[JField]) =>
        val root = fields.head
        val subtree = ArrayBuffer[JValue]()
        val transformed = transformNode(root.name, root.value, Stack(), 0, key, subtree) match {
          case JObject(fields: Seq[JField]) => List(JObject(fields))
        }

        JArray(
          if (key.isDefined && !subtree.isEmpty) subtree.toList else transformed
        )
    }

  }

  def transformNode(title: String, value: JValue, path: Stack[String], depth: Int, key: Option[String], subtree: ArrayBuffer[JValue]): JValue = {
    val v = value match {
      case JObject(fields: Seq[JField]) =>
        JObject(List(
          JField(
            name = "title",
            value = JString(title)
          ),
          JField(
            name = "folder",
            value = JBool(value = true)
          ),
          JField(
            name = "key",
            value = JString(path.reverse.mkString + "/" + title)
          ),
          JField(
            name = "children",
            value = JArray(
              fields map { field =>
                val node = transformNode(field.name, field.value, path push ("/" + title), depth + 1, key, subtree)
                node
              }
            )
          )
        ))
      case JArray(values: Seq[JValue]) =>
        JArray(values.zipWithIndex.map { v =>
          val node = transformNode(title, v._1, path push (s"/$title[${v._2}]"), depth, key, subtree)
          node
        })
      case JString(s) => JObject(List(
        JField(
          name = "title",
          value = JString(title)
        ),
        JField(
          name = "folder",
          value = JBool(value = true)
        ),
        JField(
          name = "children",
          value = JArray(List(JObject(List(
            JField(
              name = "title",
              value = JString(s)
            )
          ))))
        )

      ))
    }
    if (key != None && key.get == path.reverse.mkString) subtree append v
    v
  }

}