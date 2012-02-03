package views

import org.bson.types.ObjectId
import play.api.data.Form
import java.text.SimpleDateFormat
import java.util.Date
import play.templates.JavaExtensions
import collection.JavaConverters._

/**
 * Helper methods for the view layer
 */
object Helpers {

  val PAGE_SIZE = 12
  val DEFAULT_THUMBNAIL = "/assets/images/dummy-object.png"

  // ~~~ url building

  def getThumbnailUrl(thumbnail: Option[ObjectId], size: Int = 100) = thumbnailUrl(thumbnail, size)

  def thumbnailUrl(thumbnail: Option[ObjectId], size: Int = 100) = thumbnail match {
    case Some(t) => "/thumbnail/%s/%s".format(t, size)
    case None => DEFAULT_THUMBNAIL
  }

  // ~~~ template helpers

  def shorten(source: String, length: java.lang.Integer) = if(source.length() > length) source.substring(0, length) + "..." else source

  val niceTimeFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm")

  def niceTime(timestamp: Long) = niceTimeFormat.format(new Date(timestamp))

  def niceTime(timestamp: Date) = niceTimeFormat.format(timestamp)

  def niceText(text: String) = JavaExtensions.nl2br(text)

  // ~~~ Form helpers, for non-dynamic forms
  
  def hasErrors(form: Form[AnyRef]) = if(form != null) !form.globalErrors.isEmpty else false
  
  def listGlobalErrors(form: Form[AnyRef]) = if(form != null) form.globalErrors.map(_.message).toList.asJava
  
  def showError(field: String, form: Form[AnyRef]) = if (form != null) form.errors(field).headOption.map(_.message).getOrElse("") else ""

  def showValue(field: String, form: Form[AnyRef]) = if (form != null) form.data.get(field).getOrElse("") else ""

  // ~~~ automatically generated validation rules
  
  def printValidationRules(field: String) = ""

}

