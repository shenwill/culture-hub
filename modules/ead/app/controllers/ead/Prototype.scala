package controllers.ead

import controllers.DelvingController
import play.api.mvc._
import play.api._
import play.api.Play.current
import scala.xml._
import net.liftweb.json._
import collection.immutable.Stack
import collection.mutable.ArrayBuffer
import com.wordnik.swagger.annotations.{ ApiParam, ApiOperation, Api }
import javax.ws.rs.PathParam

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */
@Api(value = "/experiments/ead", listingPath = "/api-docs.{format}/experiments/ead", description = "EAD prototype")
object Prototype extends DelvingController {

  val source = "apeEAD_SE_KrA_0058.xml"

  @ApiOperation(value = "Sample data")
  def sampleData = Action {
    implicit request =>

      Play.resourceAsStream(source) map {
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

  @ApiOperation(value = "Sample view")
  def sampleView = Action {
    implicit request =>
      Ok(Template())
  }

  @ApiOperation(
    value = "Render a tree so that it can be displayed by FancyTree",
    notes = "Returns the JSON representation of the tree",
    responseClass = "tree",
    httpMethod = "GET"
  )
  def tree(@ApiParam(value = "Optional path for which to render a subtree") path: Option[String],
    @ApiParam(value = "Whether or not to limit the depth of the tree, for lazy loading (true by default)") limited: Boolean) = Action {
    implicit request =>

      Play.resourceAsStream(source) map {
        resourceStream =>
          {
            val source = Source.fromInputStream(resourceStream)
            val xml = scala.xml.XML.load(source)
            val json = Xml.toJson(xml)
            val unlimited = request.queryString.getFirst("limited").map(_ == "false").getOrElse(false)
            val transformed = transformToViewTree(json, request.queryString.getFirst("path"), unlimited)

            Ok(pretty(net.liftweb.json.render(transformed))).as(JSON)
          }
      } getOrElse {
        InternalServerError("Couldn't find test resource")
      }

  }

  def transformToViewTree(json: JValue, key: Option[String], unlimited: Boolean = false): JValue = {
    json match {
      case o @ JObject(fields: Seq[JField]) =>
        val root = fields.head
        val subtree = ArrayBuffer[JValue]()
        val transformed = transformNode(root.name, root.value, Stack(), 0, key, subtree, if (unlimited) -1 else 1) match {
          case JObject(fields: Seq[JField]) => List(JObject(fields))
        }

        JArray(
          if (key.isDefined && !subtree.isEmpty) subtree.toList else transformed
        )
      case other @ _ => throw new RuntimeException("Huh? Unknown node type: " + other)
    }

  }

  def transformNode(title: String,
    value: JValue,
    path: Stack[String],
    depth: Int, key: Option[String],
    subtree: ArrayBuffer[JValue],
    depthLimit: Int = 1): JValue = {
    val pathMatched = key != None && key.get == path.reverse.mkString
    val v = value match {
      case JObject(fields: Seq[JField]) =>
        val baseFields = List(
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
          )
        )
        val renderedFields = if ((depth >= depthLimit && depthLimit > -1) && (key.isEmpty || (key.isDefined && !subtree.isEmpty))) {
          baseFields ::: List(
            JField(
              name = "lazy",
              value = JBool(value = true)
            )
          )

        } else baseFields ::: List(
          JField(
            name = "children",
            value = JArray(
              fields map { field =>
                val node = transformNode(field.name, field.value, path push ("/" + title), depth + 1, key, subtree, depthLimit)
                node
              }
            )
          )
        )
        JObject(renderedFields)
      case JArray(values: Seq[JValue]) =>
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
            name = "children",
            value = JArray(values.zipWithIndex.map { v =>
              val node = transformNode(title, v._1, path push (s"/$title[${v._2}]"), depth + 1, key, subtree, depthLimit)
              node
            })
          )))
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
          value = JArray(
            List(JObject(List(
              JField(
                name = "title",
                value = JString(s)
              )
            ))))
        )

      ))
    }
    if (pathMatched) subtree append v
    v
  }

}