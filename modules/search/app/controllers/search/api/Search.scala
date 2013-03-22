package controllers.search.api

import play.api.mvc._
import core.Constants._
import core.indexing.IndexField._
import core.search.{ SearchService, SOLRSearchService }
import play.api.libs.concurrent.Promise
import controllers.{ BoundController, OrganizationConfigurationAware }
import play.api.Logger
import core.{ OrganizationCollectionLookupService, HubModule }
import play.api.cache.Cache
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

/**
 * Search API
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object Search extends BoundController(HubModule) with Search

trait Search extends Controller with OrganizationConfigurationAware { this: Controller with BoundController with OrganizationConfigurationAware =>

  val organizationCollectionLookupService = inject[OrganizationCollectionLookupService]

  // TODO once refactoring is done, inject via subcut
  val searchService: SearchService = new SOLRSearchService

  def searchApi(orgId: String, provider: Option[String], dataProvider: Option[String], collection: Option[String]) = OrganizationConfigured {
    Action {
      implicit request =>
        Async {
          Promise.pure {

            if (!request.path.contains("api")) {
              Logger("CultureHub").warn("Using deprecated API call " + request.uri)
            }

            val itemTypes = Cache.getOrElse("itemTypes", 300) {
              organizationCollectionLookupService.findAll.map(_.itemType).distinct
            }

            val orgIdFilter = "%s:%s".format(ORG_ID.key, configuration.orgId)

            val itemTypesFilter = ("(%s)".format(
              itemTypes.map(t => "%s:%s".format(RECORD_TYPE.key, t.itemType)).mkString(" OR ")
            ))

            val hiddenQueryFilters = if (itemTypes.isEmpty) List(orgIdFilter) else List(itemTypesFilter, orgIdFilter)

            searchService.getApiResult(request.queryString, request.host, hiddenQueryFilters)

          } map {
            // CORS - see http://www.w3.org/TR/cors/
            result =>
              result.withHeaders(
                ("Access-Control-Allow-Origin" -> "*"),
                ("Access-Control-Allow-Methods" -> "GET, POST, OPTIONS"),
                ("Access-Control-Allow-Headers" -> "X-Requested-With"),
                ("Access-Control-Max-Age" -> "86400")

              )
          }
        }
    }
  }

}
