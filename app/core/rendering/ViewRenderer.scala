/*
 * Copyright 2012 Delving B.V.
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

package core.rendering

import scala.xml.Node
import xml.XML
import collection.mutable.{HashMap, ArrayBuffer}
import collection.mutable.Stack
import models.GrantType
import play.api.Logger
import org.w3c.dom.{Document, Node => WNode}
import play.libs.XPath
import collection.JavaConverters._
import javax.xml.parsers.DocumentBuilderFactory
import java.io.{ByteArrayInputStream, File}

/**
 * View Rendering mechanism. Reads a ViewDefinition from a given record definition, and applies it onto the input data (a node tree).
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object ViewRenderer {

  val log = Logger("CultureHub")

  val dbFactory = DocumentBuilderFactory.newInstance
  dbFactory.setNamespaceAware(true)
  val dBuilder = dbFactory.newDocumentBuilder

  def renderView(viewDefinitionSource: File, view: String, record: String, userGrantTypes: List[GrantType], namespaces: Map[String, String]): RenderNode = {
    val prefix = viewDefinitionSource.getName.substring(0, viewDefinitionSource.getName.indexOf("-"))
    val xml = XML.loadFile(viewDefinitionSource)
    (xml \ "view").filter(v => (v \ "@name").text == view).headOption match {
      case Some(viewDefinition) =>
        renderView(prefix, viewDefinition, record, userGrantTypes, namespaces)
      case None => throw new RuntimeException("Could not find view definition '%s' in file '%s'".format(view, viewDefinitionSource.getAbsolutePath))
    }
  }

  def renderView(prefix: String, viewDefinition: Node, rawRecord: String, userGrantTypes: List[GrantType], namespaces: Map[String, String]): RenderNode = {

    val record = dBuilder.parse(new ByteArrayInputStream(rawRecord.getBytes("utf-8")))

    val result = RenderNode("root")
    val treeStack = Stack(result)
    val root = viewDefinition
    walk(root, record)

    implicit def richerNode(n: Node) = new {
        def attr(name: String) = {
          val sel = "@" + name
          (n \ sel).text
        }
      }

    def walk(viewDefinitionNode: Node, dataNode: WNode) {

      viewDefinitionNode.foreach {
        n =>
          log.debug("Node " + n)
          if (n.label != "#PCDATA") {

            // common attributes
            val label = n.attr("label")
            val role = n.attr("role")
            val path = n.attr("path")
            val queryLink = {
              val l = n.attr("queryLink")
              if (l.isEmpty) false else l.toBoolean
            }

            val roleList = role.split(",").map(_.trim).filterNot(_.isEmpty).toList

            n.label match {

              case "view" => enterOne(n, dataNode, "root")
              case "row" => enterOne(n, dataNode, "row")
              case "column" => enterOne(n, dataNode, "column", 'id -> n.attr("id"))
              case "field" =>
                if (hasAccess(roleList)) {
                  val values = fetchPaths(dataNode, path.split(",").map(_.trim).toList, namespaces)
                  appendNode("field", values.headOption, 'label -> label, 'queryLink -> queryLink) { renderNode => }
                }
              case "enumeration" =>
                if (hasAccess(roleList)) {

                  appendSimple("enumeration", 'label -> label, 'queryLink -> queryLink, 'type -> n.attr("type"), 'separator -> n.attr("separator")) {
                    list =>

                      if (!n.child.isEmpty) {
                        throw new RuntimeException("An enumeration cannot have child elements!")
                      }

                      val values = fetchPaths(dataNode, path.split(",").map(_.trim).toList, namespaces)
                      values foreach {
                        v => list += RenderNode("text", Some(v))
                      }
                  }
                }

              case "list" =>
                if(hasAccess(roleList)) {
                  
                  XPath.selectNodes(path, dataNode, namespaces.asJava).asScala.foreach {
                    child =>
                      enterOne(n, child, "list")
                  }
                  
                  
                  
                }

              case u@_ => throw new RuntimeException("Unknown element '%s'".format(u))


            }
          }

      }
    }

    /** appends a new RenderNode to the result tree and walks one level deeper **/
    def enterOne(viewDefinitionNode: Node, dataNode: WNode, nodeType: String, attr: (Symbol, Any)*) {
      log.debug("Entered " + viewDefinitionNode.label)
      val entered = RenderNode(nodeType)
      attr foreach {
        entered addAttr _
      }
      treeStack.head += entered
      treeStack.push(entered)
      viewDefinitionNode.child foreach {
        n =>
          log.debug("Node " + n)
          walk(n, dataNode)
      }
      treeStack.pop()

    }

    /** appends a new RenderNode without content to the result tree and performs an operation on it **/
    def appendSimple(nodeType: String, attr: (Symbol, Any)*)(block: RenderNode => Unit) {
      appendNode(nodeType, None, attr : _ *)(block)
    }


    /** appends a new RenderNode to the result tree and performs an operation on it **/
    def appendNode(nodeType: String, text: Option[String] = None, attr: (Symbol, Any)*)(block: RenderNode => Unit) {
      val newNode = RenderNode(nodeType, text)
      attr foreach {
        newNode addAttr _
      }
      treeStack.head += newNode
      treeStack.push(newNode)
      block(newNode)
      treeStack.pop()
    }

    def hasAccess(roles: List[String]) = {
      roles.isEmpty || (userGrantTypes.exists(gt => roles.contains(gt.key) && gt.origin == prefix) || userGrantTypes.exists(gt => gt.key == "own" && gt.origin == "System"))
    }

    result

  }


  def fetchPaths(dataNode: Object, paths: Seq[String], namespaces: Map[String, String]): Seq[String] = {
    (for (path <- paths) yield {
      XPath.selectText(path, dataNode, namespaces.asJava)
    })
  }

}

/**
 * A node used to hold the structure to be rendered
 */
case class RenderNode(nodeType: String, value: Option[String] = None) {

  private val contentBuffer = new ArrayBuffer[RenderNode]
  private val attributes = new HashMap[String, Any]

  def content: List[RenderNode] = contentBuffer.toList

  def +=(node: RenderNode) {
    contentBuffer += node
  }

  def attr(key: String) = attributes(key)

  def addAttr(key: String, value: AnyRef) = attributes + (key -> value)

  def addAttr(element: (Symbol, Any)) {
    attributes += (element._1.name -> element._2)
  }

  def text: String = value.getOrElse("")

  override def toString = """[%s] - %s - %s""".format(nodeType, value, attributes.toString())
}

case object RenderNode {

  def visit(n: RenderNode, level: Int = 0) {
    for(i <- 0 to level) print(" ")
    print(n.toString)
    println()
    for(c <- n.content) {
      visit(c, level + 1)
    }
  }
}