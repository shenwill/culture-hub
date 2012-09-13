package controllers.dos

import play.api.mvc._

import org.bson.types.ObjectId
import com.mongodb.gridfs.GridFSDBFile
import com.mongodb.casbah.commons.MongoDBObject
import play.api.libs.iteratee.Enumerator
import controllers.DomainConfigurationAware
import models.DomainConfiguration

/**
 * Common controller for handling files, no matter from where.
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object FileStore extends Controller with DomainConfigurationAware {

  // ~~~ public HTTP API

  def get(id: String): Action[AnyContent] = DomainConfigured {
    Action {
      implicit request =>
        if (!ObjectId.isValid(id)) {
          BadRequest("Invalid ID " + id)
        } else {
          val oid = new ObjectId(id)
          fileStore(configuration).findOne(oid) match {
            case Some(file) =>
              Ok.stream(Enumerator.fromStream(file.inputStream)).withHeaders(
                (CONTENT_DISPOSITION -> ("attachment; filename=" + file.filename)),
                (CONTENT_LENGTH -> file.length.toString),
                (CONTENT_TYPE -> file.contentType))
            case None =>
              NotFound("Could not find file with ID " + id)
          }
        }
    }
  }


  // ~~~ public scala API

  def getFilesForItemId(id: String)(implicit configuration: DomainConfiguration): List[StoredFile] =
    fileStore(configuration).
      find(MongoDBObject(ITEM_POINTER_FIELD -> id)).
      map(f => fileToStoredFile(f)).toList

  // ~~~ private

  private[dos] def fileToStoredFile(f: GridFSDBFile)(implicit configuration: DomainConfiguration) = {
    val id = f.getId.asInstanceOf[ObjectId]
    val thumbnail = if (FileUpload.isImage(f)) {
      fileStore(configuration).findOne(MongoDBObject(FILE_POINTER_FIELD -> id)) match {
        case Some(t) => Some(t.id.asInstanceOf[ObjectId])
        case None => None
      }
    } else {
      None
    }
    StoredFile(id, f.getFilename, f.getContentType, f.getLength, thumbnail)
  }
}