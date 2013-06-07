package controllers.organization

import play.api.i18n.Messages
import controllers._
import play.api.mvc.Action
import models.HubUser
import core._
import core.collection.OrganizationCollection
import com.mongodb.casbah.Imports._
import controllers.Token

/**
 *
 * @author Gerald de Jong <gerald@delving.eu>
 * @author Manuel Bernhardt <manuel@delving.eu>
 */
class Organizations extends DelvingController {

  val harvestCollectionLookupService = inject[HarvestCollectionLookupService]

  def index(orgId: String, language: Option[String]) = OrganizationBrowsing {
    Action {
      implicit request =>
        if (organizationServiceLocator.byDomain.exists(orgId)) {
          val members: List[HubUser] = HubUser.dao.listOrganizationMembers(orgId).flatMap(HubUser.dao.findByUsername(_))
          val collections: Seq[OrganizationCollection] = harvestCollectionLookupService.findAllNonEmpty(configuration.orgId, None)
          val lang = language.getOrElse(getLang)
          Ok(Template(
            'orgId -> orgId,
            'orgName -> organizationServiceLocator.byDomain.getName(orgId, "en").getOrElse(orgId),
            'isMember -> HubUser.dao.findByUsername(connectedUser).map(u => u.organizations.contains(orgId)).getOrElse(false),
            'members -> members,
            'collections -> collections,
            'currentLanguage -> lang

          ))
        } else {
          NotFound(Messages("hub.CouldNotFindOrganization", orgId))
        }
    }
  }

  def listAsTokens(q: String) = Root {
    Action {
      implicit request =>
        val tokens = organizationServiceLocator.byDomain.queryByOrgId(q).map { org => Token(org.orgId, org.orgId) }
        Json(tokens)
    }
  }

}