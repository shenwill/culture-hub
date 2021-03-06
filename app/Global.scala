/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

import actors._
import com.github.cb372.metrics.sigar.SigarMetrics
import com.yammer.metrics.scala.{ Counter, Instrumented }
import controllers.{ MultitenancySupport, ErrorReporter, ApplicationController }
import core.{ HubModule, CultureHubPlugin }
import java.util.concurrent.TimeUnit
import models.OrganizationConfiguration
import play.api.libs.concurrent._
import akka.actor._
import play.api._
import play.api.mvc.{ Action, WithFilters, Handler, RequestHeader }
import play.api.Play.current
import play.extras.iteratees.GzipFilter
import util.{ OrganizationConfigurationResourceHolder, OrganizationConfigurationHandler }
import eu.delving.culturehub.BuildInfo
import play.api.mvc.Results._

object Global extends WithFilters(new GzipFilter()) with Instrumented {

  override def onStart(app: Application) {

    if (!Play.isTest) {
      println("""
                ____       __      _
               / __ \___  / /   __(_)___  ____ _
              / / / / _ \/ / | / / / __ \/ __ `/
             / /_/ /  __/ /| |/ / / / / / /_/ /
            /_____/\___/_/ |___/_/_/ /_/\__, /
                                       /____/
               ______      ____                  __  __      __
              / ____/_  __/ / /___  __________  / / / /_  __/ /_
             / /   / / / / / __/ / / / ___/ _ \/ /_/ / / / / __ \
            / /___/ /_/ / / /_/ /_/ / /  /  __/ __  / /_/ / /_/ /
            \____/\__,_/_/\__/\__,_/_/   \___/_/ /_/\__,_/_.___/

            http://www.delving.eu
            ~~~~~~~~~~~~~~~~~~~~~

            Version %s

            SIP-App Version %s
            SIP-Core Version %s

              """.format(BuildInfo.cultureHub, BuildInfo.sipApp, BuildInfo.sipCore))
    }

    // ~~~ bootstrap jobs
    if (!Play.isTest) {

      // token expiration
      Akka.system.actorOf(Props[TokenExpiration])

      // DoS
      Akka.system.actorOf(Props[TaskQueueActor])

      // routes access logger
      Akka.system.actorOf(Props[RouteLogger], name = "routeLogger")

    }

    OrganizationConfigurationHandler.registerResourceHolder(domainRequestCounters, initFirst = true)
    // TODO find a good way to package the native sigar libraries during dist
    //SigarMetrics.getInstance().registerGauges()

    // ~~~ load test data

    if (Play.isDev) {
      util.TestDataLoader.load(Map("samples" -> Seq("sample1", "sample2")))
    }

  }

  override def onStop(app: Application) {
    if (!Play.isTest) {
      // close all Mongo connections
      import models.HubMongoContext._
      close()
    }
  }

  override def onError(request: RequestHeader, ex: Throwable) = {
    if (Play.isProd) {
      import play.api.mvc.Results._
      InternalServerError(views.html.errors.error(Some(ex), None))
    } else {
      super.onError(request, ex)
    }
  }

  override def onHandlerNotFound(request: RequestHeader) = {

    if (Play.isProd) {
      InternalServerError(views.html.errors.notFound(request, "", None))
    } else {
      super.onHandlerNotFound(request)
    }

  }

  override def getControllerInstance[A](controllerClass: Class[A]): A = {

    if (classOf[ApplicationController].isAssignableFrom(controllerClass)) {
      val ct = controllerClass.getConstructors()(0)
      ct.newInstance(HubModule).asInstanceOf[A]
    } else {
      super.getControllerInstance(controllerClass)
    }
  }

  val apiController = new controllers.api.Api()(HubModule)

  val requestsMeter = metrics.meter("requests", eventType = "requests", unit = TimeUnit.SECONDS)

  val domainRequestCounters = new OrganizationConfigurationResourceHolder[OrganizationConfiguration, Counter]("domainRequestsCounters") {

    protected def resourceConfiguration(configuration: OrganizationConfiguration): OrganizationConfiguration = configuration

    protected def onAdd(resourceConfiguration: OrganizationConfiguration): Option[Counter] = {
      Some(metrics.counter(s"${resourceConfiguration.orgId}.requests"))
    }

    protected def onRemove(removed: Counter) {
      // TODO
    }
  }

  override def onRouteRequest(request: RequestHeader): Option[Handler] = {

    requestsMeter.mark()

    val domain: String = request.queryString.get("domain").map(v => v.head).getOrElse(request.domain)

    // check if we access a configured domain
    if (!OrganizationConfigurationHandler.hasConfiguration(domain)) {
      Logger("CultureHub").debug("Accessed invalid domain %s, redirecting...".format(domain))
      Some(controllers.Default.redirect(Play.configuration.getString("defaultDomainRedirect").getOrElse("http://www.delving.eu")))
    } else {
      OrganizationConfigurationHandler.getByDomain(domain).map { implicit configuration =>
        domainRequestCounters.getResource(configuration) += 1

        val routes = CultureHubPlugin.getEnabledPlugins.flatMap(_.routes)

        val routeLogger = Akka.system.actorFor("akka://application/user/routeLogger")
        val apiRouteMatcher = """^/api/(.)*""".r
        val matcher = apiRouteMatcher.pattern.matcher(request.uri)

        if (matcher.matches()) {
          // log route access, for API calls
          routeLogger ! RouteRequest(request)

          // TODO proper routing for search
          if (request.queryString.contains("explain") && request.queryString("explain").head == "true" && !request.path.contains("search")) {
            // redirect to the standard explain response
            return Some(apiController.explainPath(request.path))
          }
        }

        // TODO retire this mechanism now that there's one provided by the framework
        // poor man's modular routing, based on CultureHub plugins
        // thou shalt not try to improve this code, it's meant to be provided by the framework at some point

        val matches = routes.flatMap(r => {
          val matcher = r._1._2.pattern.matcher(request.path)
          if (request.method == r._1._1 && matcher.matches()) {
            val pathElems = for (i <- 1 until matcher.groupCount() + 1) yield matcher.group(i)
            Some((pathElems.toList, r._2))
          } else {
            None
          }
        })

        if (matches.headOption.isDefined) {
          val handlerCall = matches.head
          val handler = handlerCall._2(handlerCall._1, request.queryString.filterNot(_._2.isEmpty).map(qs => qs._1 -> qs._2.head))
          Some(handler)
        } else {

          // use Play routers defined by plugins now that we have them
          val pluginRouters = CultureHubPlugin.getEnabledPlugins.flatMap(_.router)

          val handler: Option[Handler] = super.onRouteRequest(request) map { handler =>
            Some(handler)
          } getOrElse {
            pluginRouters.find(r => r.handlerFor(request).isDefined).flatMap { router =>
              router.handlerFor(request)
            }
          }
          handler
        }

      } getOrElse {
        Some(
          Action {
            ServiceUnavailable(views.html.errors.serviceUnavailable())
          }
        )
      }
    }
  }
}
