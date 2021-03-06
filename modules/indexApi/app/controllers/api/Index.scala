package controllers.api

import controllers.{ ApiDescription, DelvingController, RenderingExtensions }
import play.api.mvc._
import play.api.libs.concurrent.Promise
import scala.xml._
import core.Constants._
import core.indexing.IndexField._
import models.{ OrganizationConfiguration, MetadataItem, MetadataCache }
import org.joda.time.format.ISODateTimeFormat
import collection.mutable.{ ArrayBuffer, ListBuffer }
import com.mongodb.casbah.commons.MongoDBObject
import play.api.Logger
import core.{ IndexingService, ItemType, OrganizationCollectionLookupService }
import core.collection.OrganizationCollection
import play.api.libs.concurrent.Execution.Implicits._
import collection.mutable
import com.escalatesoft.subcut.inject.BindingModule

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object Index {
  val CACHE_COLLECTION = "indexApiItems"
}

class Index(implicit val bindingModule: BindingModule) extends DelvingController with RenderingExtensions {

  def explain(path: List[String]) = path match {
    case Nil =>
      Some(
        ApiDescription("""The Index API makes it possible to send custom items to be indexed.
        |
        | It expects to receive an XML document containing one or more items to be indexed.
        | Each item must have an itemId attribute which serves as identifier for the item to be indexed,
        | as well as an itemType attribute which indicates the type of the item, to be used to filter it later on.
        |
        | An item contains one or more field elements that describe the data to be indexed. A field must provide a name attribute,
        | and can optionally specify:
        | - a fieldType attribute which is used by the indexing mechanism (default value: "text")
        | - a facet attribute which means that the field is to be made available as a facet (default value: false)
        |
        | The possible values for fieldType are: string, location, int, single, text, date, link
        |
        | For example:
        |
        | <indexRequest>
        |   <indexItem itemId="123" itemType="book">
        |     <field name="title" fieldType="string">The Hitchhiker's Guide to the Galaxy</field>
        |     <field name="author" fieldType="string" facet="true">Douglas Adams</field>
        |   </indexItem>
        | </indexRequest>
        |
        |
        | It is possible to remove existing items by specifying the delete flag:
        |
        | <indexRequest>
        |   <indexItem itemId="123" itemType="book" delete="true" />
        | </indexRequest>
        |
        |
        | Additionally, there is a number of optional system fields that can be specified, and that help to trigger additional functionality:
        |
        | <indexRequest>
        |   <indexItem itemId="123" itemType="book">
        |     <systemField name="thumbnail">http://path/to/thumbnail</field>
        |   </indexItem>
        | </indexRequest>
        |
        | The possible systemField names are: collection, thumbnail, landingPage, provider, owner, title, description, fullText
        """.stripMargin)
      )
    case _ => None
  }

  def status = MultitenantAction {
    implicit request =>
      // TODO provide some stats
      Ok
  }

  def submit = MultitenantAction(parse.tolerantXml) {
    implicit request =>
      {
        Async {
          Promise.pure {

            if (configuration.isReadOnly) {
              <indexResponse>
                <error>The system is in read-only mode and cannot process the request</error>
              </indexResponse>
            } else {
              val (valid, invalid) = parseIndexRequest(configuration.orgId, request.body)

              var indexed: Int = 0
              var deleted: Int = 0

              for (i <- valid.zipWithIndex) {
                val item = i._1
                val index = i._2
                val cache = MetadataCache.get(configuration.orgId, Index.CACHE_COLLECTION, item.itemType)
                if (item.deleted) {
                  cache.remove(item.itemId)
                  indexingServiceLocator.byDomain.deleteByQuery("""id:%s_%s_%s""".format(item.orgId, item.itemType, item.itemId))
                  deleted += 1
                } else {
                  val cacheItem = MetadataItem(collection = Index.CACHE_COLLECTION, itemType = item.itemType, itemId = item.itemId, xml = Map("raw" -> item.rawXml), schemaVersions = Map("raw" -> "1.0.0"), index = index)
                  cache.saveOrUpdate(cacheItem)
                  indexingServiceLocator.byDomain.stageForIndexing(item.toIndexDocument)
                  indexed += 1
                }
              }
              indexingServiceLocator.byDomain.commit

              val invalidItems = invalid.map(i => <invalidItem><error>{ i._1 }</error><item>{ i._2 }</item></invalidItem>)

              <indexResponse>
                <totalItemCount>{ valid.size + invalid.size }</totalItemCount>
                <indexedItemCount>{ valid.filterNot(_.deleted).size }</indexedItemCount>
                <deletedItemCount>{ valid.filter(_.deleted).size }</deletedItemCount>
                <invalidItemCount>{ invalid.size }</invalidItemCount>
                <invalidItems>{ invalidItems }</invalidItems>
              </indexResponse>
            }

          } map {
            response => Ok(response)
          }
        }
      }
  }

  private def parseIndexRequest(orgId: String, root: NodeSeq): (List[IndexItem], List[(String, NodeSeq)]) = {
    val validItems = new ListBuffer[IndexItem]()
    val invalidItems = new ListBuffer[(String, NodeSeq)]()
    for (item <- root \\ "indexItem") {

      val requiredAttributes = Seq("itemId", "itemType")
      val hasRequiredAttributes = requiredAttributes.foldLeft(true) {
        (r, c) => r && item.attribute(c).isDefined
      }
      if (!hasRequiredAttributes) {
        invalidItems += "Item misses required attributes 'itemId' or 'itemType'" -> item
      } else {
        val itemId = item.attribute("itemId").get.text
        val itemType = item.attribute("itemType").get.text

        val deleted = item.attribute("delete").map(_.text == "true").getOrElse(false)

        // TODO check more field syntax
        val invalidDates = item.nonEmptyChildren.filter(f => f.label == "field" && f.attribute("fieldType").isDefined && f.attribute("fieldType").get.head.text == "date") flatMap {
          f =>
            try {
              ISODateTimeFormat.dateTime().parseDateTime(f.text)
              None
            } catch {
              case t: Throwable => Some(("Invalid date field '%s' with value '%s'".format((f \ "@name").text, f.text) -> item))
            }
        }

        if (!invalidDates.isEmpty) {
          invalidItems ++= invalidDates
        } else {
          val indexItem = IndexItem(
            orgId = orgId,
            itemId = itemId,
            itemType = itemType,
            rawXml = item.toString(),
            deleted = deleted
          )
          validItems += indexItem
        }
      }
    }
    (validItems.toList, invalidItems.toList)

  }

  def reIndex = MultitenantAction {
    implicit request =>
      val service = new IndexItemOrganizationCollectionLookupService()
      val itemTypes = service.findAll.map { _.itemType }

      var reIndexed = 0
      val error = new ArrayBuffer[String]()
      itemTypes map { t =>
        val cache = MetadataCache.get(configuration.orgId, Index.CACHE_COLLECTION, t)
        cache.iterate() foreach { item =>
          try {
            indexingServiceLocator.byDomain.stageForIndexing(IndexItem(configuration.orgId, item).toIndexDocument)
            reIndexed += 1
          } catch {
            case t: Throwable =>
              val id = configuration.orgId + "_" + item.itemType + "_" + item.itemId
              Logger("IndexApi").error("Could not index item " + id, t)
              error += id
          }
        }
      }
      indexingServiceLocator.byDomain.commit

      Ok(s"ReIndexed $reIndexed items successfully, error for ${error.mkString(", ")}")
  }

}

case class IndexItem(orgId: String,
    itemId: String,
    itemType: String,
    rawXml: String,
    deleted: Boolean = false) {

  def toIndexDocument: IndexingService#IndexDocument = {
    val doc = new mutable.HashMap[String, mutable.Set[Any]] with mutable.MultiMap[String, Any]

    val document = XML.loadString(rawXml).nonEmptyChildren
    val fields = document.filter(_.label == "field")

    // content fields
    fields.filter(_.attribute("name").isDefined).foreach {
      field =>
        val name = (field \ "@name").text
        val dataType = field.attribute("fieldType").getOrElse("text")
        val isFacet = field.attribute("facet").isDefined && (field \ "@facet").text == "true"

        val acceptedPrefixes = "dc|dcterms|icn|europeana|delving"

        val indexFieldName = if (name.contains(":") && name.split(":").head.matches(acceptedPrefixes)) {
          "%s_%s".format(name.replaceFirst(":", "_"), dataType)
        } else if (name.contains("_") && name.split("_").head.matches(acceptedPrefixes)) {
          "%s_%s".format(name, dataType)
        } else {
          "custom_%s_%s".format(name, dataType)
        }

        doc.addBinding(indexFieldName, field.text)

        if (isFacet) {
          doc.addBinding(indexFieldName + "_facet", field.text)
        }
    }

    // system fields
    val allowedSystemFields = List("thumbnail", "landingPage", "provider", "owner", "title", "description", "collection", "fullText")

    val systemFields = document.filter(_.label == "systemField")
    systemFields.filter(f => f.attribute("name").isDefined && allowedSystemFields.contains(f.attribute("name").get.text)).foreach {
      field =>
        val name = (field \ "@name").text
        doc.addBinding("delving_" + name, field.text)
    }

    // mandatory fields
    val id = "%s_%s_%s".format(orgId, itemType, itemId)
    doc += (ID -> id)
    doc += (HUB_ID -> id)
    doc += (ORG_ID -> orgId)
    doc += (RECORD_TYPE -> itemType)

    doc.toMap
  }

}

case object IndexItem {
  def apply(orgId: String, item: MetadataItem): IndexItem = IndexItem(orgId, item.itemId, item.itemType, item.xml("raw"), false)
}

class IndexItemOrganizationCollectionLookupService extends OrganizationCollectionLookupService {

  def findAll(implicit configuration: OrganizationConfiguration): Seq[OrganizationCollection] = MetadataCache.getItemTypes(configuration.orgId, Index.CACHE_COLLECTION).map { t =>
    new OrganizationCollection {
      def getCreator: String = "system"
      def getOwner: String = configuration.orgId
      val itemType: ItemType = t
      val spec: String = "indexApiItems_" + itemType.itemType
    }
  }

  def findBySpecAndOrgId(spec: String, orgId: String)(implicit configuration: OrganizationConfiguration): Option[OrganizationCollection] = findAll.find(_.spec == spec)
}