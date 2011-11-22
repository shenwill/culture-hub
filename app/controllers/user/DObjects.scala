package controllers.user

import play.mvc.results.Result
import extensions.JJson._
import com.novus.salat.dao.SalatDAOUpdateError
import play.libs.Codec
import controllers._
import com.mongodb.WriteConcern
import org.bson.types.ObjectId
import com.mongodb.casbah.Imports._
import play.data.validation.Annotations._
import java.util.Date
import controllers.dos.FileUploadResponse
import extensions.JJson
import models._

/**
 * Controller for manipulating user objects (creation, update, ...)
 * listing and display is done in the other controller that does not require authentication
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object DObjects extends DelvingController with UserSecured {

  private def load(id: String): String = {
    val availableCollections = UserCollection.browseByUser(connectedUserId, connectedUserId).toList map { c => CollectionReference(c._id, c.name) }
    DObject.findByIdUnsecured(id) match {
        case None => JJson.generate(ObjectModel(availableCollections = availableCollections))
        case Some(anObject) => {
          val model = ObjectModel(
          Some(anObject._id),
          anObject.name,
          anObject.description,
          anObject.user_id,
          anObject.visibility.value,
          anObject.collections,
          availableCollections,
          anObject.files map {f => FileUploadResponse(name = f.name, size = f.length, url = "/file/" + f.id, thumbnail_url = f.thumbnailUrl, delete_url = "/file/" + f.id, selected = Some(f.id) == anObject.thumbnail_file_id, id = f.id.toString)},
          anObject.thumbnail_file_id.toString)
          val generated: String = JJson.generate[ObjectModel](model)
          generated
        }
      }
  }

  def dobject(id: String): Result = {
    renderArgs += ("viewModel", classOf[ObjectModel])
    Template('id -> Option(id), 'uid -> Codec.UUID(), 'data -> load(id))
  }

  def objectSubmit(data: String, uid: String): Result = {
    val objectModel: ObjectModel = parse[ObjectModel](data)
    validate(objectModel).foreach { errors => return JsonBadRequest(objectModel.copy(errors = errors)) }

    val files = controllers.dos.FileUpload.getFilesForUID(uid)

    /** finds thumbnail candidate for an object, "activate" thumbnails (for easy lookup) and returns the OID of the thumbnail candidate image file **/
    def activateThumbnail(itemId: ObjectId, fileId: String) = if(!fileId.isEmpty) {
        controllers.dos.FileUpload.activateThumbnails(new ObjectId(fileId), itemId); Some(new ObjectId(fileId))
    } else {
      None
    }

    val persistedObject = objectModel.id match {
      case None =>
          val newObject: DObject = DObject(
            TS_update = new Date(),
            name = objectModel.name,
            description = objectModel.description,
            user_id = connectedUserId,
            userName = connectedUser,
            visibility = Visibility.get(objectModel.visibility),
            thumbnail_id = None,
            collections = objectModel.collections,
            files = files)
          val inserted: Option[ObjectId] = DObject.insert(newObject)
        inserted match {
          case Some(iid) => {
            SolrServer.indexSolrDocument(newObject.copy(_id = iid).toSolrDocument)
            controllers.dos.FileUpload.markFilesAttached(uid, iid)
            activateThumbnail(iid, objectModel.selectedFile) match {
              case Some(thumb) => DObject.updateThumbnail(iid, thumb)
              case None =>
            }
            SolrServer.commit()
            Some(objectModel.copy(id = inserted))
          }
          case None => None
        }
      case Some(id) =>
        val existingObject = DObject.findOneByID(id)
        if(existingObject == None) Error(&("user.dobjects.objectNotFound", id))
        val updatedObject = existingObject.get.copy(TS_update = new Date(), name = objectModel.name, description = objectModel.description, visibility = Visibility.get(objectModel.visibility), user_id = connectedUserId, collections = objectModel.collections, files = existingObject.get.files ++ files)
        try {
          SolrServer.indexSolrDocument(updatedObject.toSolrDocument)
          DObject.update(MongoDBObject("_id" -> id), updatedObject, false, false, WriteConcern.SAFE)
          controllers.dos.FileUpload.markFilesAttached(uid, id)
          activateThumbnail(id, objectModel.selectedFile) match {
            case Some(thumb) => DObject.updateThumbnail(id, thumb)
            case None =>
          }
          SolrServer.commit()
          Some(objectModel)
        } catch {
          case e: SalatDAOUpdateError =>
            SolrServer.rollback()
            None
          case _ =>
            SolrServer.rollback()
            None
        }
    }

    persistedObject match {
      case Some(theObject) => {
        Json(theObject)
      }
      case None => Error(&("user.dobjects.saveError", objectModel.name))
    }
  }

  def remove(id: ObjectId) = {
    if(DObject.owns(connectedUserId, id)) {
      DObject.delete(id)
      SolrServer.deleteFromSolrById(id)
      SolrServer.commit()
    } else {
      Forbidden("Big brother is watching you")
    }
  }
}

// ~~~ view models

case class ObjectModel(id: Option[ObjectId] = None,
                       @Required name: String = "",
                       @Required description: String = "",
                       owner: ObjectId = new ObjectId(),
                       visibility: Int = Visibility.PRIVATE.value,
                       collections: List[ObjectId] = List.empty[ObjectId],
                       availableCollections: List[CollectionReference] = List.empty[CollectionReference],
                       files: Seq[FileUploadResponse] = Seq.empty[FileUploadResponse],
                       selectedFile: String = "",
                       errors: Map[String, String] = Map.empty[String, String]) extends ViewModel