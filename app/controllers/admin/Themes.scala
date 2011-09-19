package controllers.admin

import controllers.DelvingController
import cake.ComponentRegistry
import com.mongodb.casbah.commons.MongoDBObject
import play.mvc.results.Result
import play.templates.Html
import extensions.CHJson
import org.bson.types.ObjectId
import models.{EmailTarget, PortalTheme}

/**
 * TODO add Access Control
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object Themes extends DelvingController {

  import views.Admin.Themes._

  def index(): AnyRef = {
    val themeList = PortalTheme.findAll
    html.index(themes = themeList.toList)
  }

  def load(id: String): Result = {
    PortalTheme.findById(id) match {
      case None => Json(ThemeViewModel())
      case Some(theme) => Json(ThemeViewModel(id = Some(theme._id), name = theme.name, templateDir = theme.templateDir, isDefault = theme.isDefault, localisedQueryKeys = theme.localiseQueryKeys, hiddenQueryFilter = theme.hiddenQueryFilter, subdomain = theme.subdomain, displayName = theme.displayName, googleAnalyticsTrackingCode = theme.googleAnalyticsTrackingCode, addThisTrackingCode = theme.addThisTrackingCode, defaultLanguage = theme.defaultLanguage, colorScheme = theme.colorScheme, solrSelectUrl = theme.solrSelectUrl, cacheUrl = theme.cacheUrl, emailTarget = theme.emailTarget, homePage = theme.homePage, metadataPrefix = theme.metadataPrefix, text = theme.text, possibleQueryKeys = theme.localiseQueryKeys))
    }
  }

  def list(): AnyRef = {
    val themeList = PortalTheme.findAll
    Json(Map("themes" -> themeList))
  }

  def themeUpdate(id: String): Html = html.theme(Option(id))

  def themeSubmit(data: String): Result = {
    val theme = CHJson.parse[ThemeViewModel](data)

    val persistedTheme = theme.id match {
      case None => {
        val inserted = PortalTheme.insert(PortalTheme(name = theme.name, templateDir = theme.templateDir, isDefault = theme.isDefault, localiseQueryKeys = theme.localisedQueryKeys, hiddenQueryFilter = theme.hiddenQueryFilter, subdomain = theme.subdomain, displayName = theme.displayName, googleAnalyticsTrackingCode = theme.googleAnalyticsTrackingCode, addThisTrackingCode = theme.addThisTrackingCode, defaultLanguage = theme.defaultLanguage, colorScheme = theme.colorScheme, solrSelectUrl = theme.solrSelectUrl, cacheUrl = theme.cacheUrl, emailTarget = theme.emailTarget, homePage = theme.homePage, metadataPrefix = theme.metadataPrefix, text = theme.text))
        inserted match {
          case Some(id) => Some(theme.copy(id = inserted))
          case None => None
        }
      }
      case Some(oid) => {
        val existing = PortalTheme.findOneByID(oid)
        if(existing == None) return NotFound("Theme with ID %s not found".format(oid))
        val updated = existing.get.copy(name = theme.name, templateDir = theme.templateDir, isDefault = theme.isDefault, localiseQueryKeys = theme.localisedQueryKeys, hiddenQueryFilter = theme.hiddenQueryFilter, subdomain = theme.subdomain, displayName = theme.displayName, googleAnalyticsTrackingCode = theme.googleAnalyticsTrackingCode, addThisTrackingCode = theme.addThisTrackingCode, defaultLanguage = theme.defaultLanguage, colorScheme = theme.colorScheme, solrSelectUrl = theme.solrSelectUrl, cacheUrl = theme.cacheUrl, emailTarget = theme.emailTarget, homePage = theme.homePage, metadataPrefix = theme.metadataPrefix, text = theme.text)
        PortalTheme.save(updated)
        Some(theme)
      }
    }

    persistedTheme match {
      case Some(theTheme) => {
        ComponentRegistry.themeHandler.update()
        Json(theTheme)
      }
      case None => Error("Error saving theme")
    }

  }

}

case class ThemeViewModel(id: Option[ObjectId] = None,
                          name: String = "",
                          templateDir: String = "",
                          isDefault: Boolean = false,
                          localisedQueryKeys: List[String] = List(),
                          possibleQueryKeys: List[String] = List(),
                          hiddenQueryFilter: Option[String] = Some(""),
                          subdomain: Option[String] = None,
                          displayName: String = "",
                          googleAnalyticsTrackingCode: Option[String] = Some(""),
                          addThisTrackingCode: Option[String] = Some(""),
                          defaultLanguage: String = "",
                          colorScheme: String = "",
                          solrSelectUrl: String = "http://localhost:8983/solr",
                          cacheUrl: String = "http://localhost:8983/services/image?",
                          emailTarget: EmailTarget = EmailTarget(),
                          homePage: Option[String] = Some(""),
                          metadataPrefix: Option[String] = Some(""),
                          text: String = "")