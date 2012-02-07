/*
 * Copyright 2011 Delving B.V.
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

package util

import extensions.ConfigurationException
import java.lang.String
import play.api.Play.current
import scala.collection.JavaConversions._
import com.mongodb.casbah.commons.MongoDBObject
import play.Play
import java.io.File
import play.api.Logger
import models.{EmailTarget, PortalTheme}

/**
 * ThemHandler taking care of loading themes (initially from YML, then from mongo)
 *
 * @author Sjoerd Siebinga <sjoerd.siebinga@gmail.com>
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 * @since 3/9/11 3:25 PM
 */
object ThemeHandler {

  private var themeList: Seq[PortalTheme] = List()

  private val defaultQueryKeys = List("dc.title", "dc.description", "dc.creator", "dc.subject", "dc.date") // todo add more default cases

  def getThemeNames: java.util.Set[String] = {
    val set: java.util.Set[String] = new java.util.TreeSet[String]
    themeList.foreach(theme => set.add(theme.name))
    set
  }

  /**
   * Look into the database if we have some themes. If we don't attempt to load from YML.
   */
  def startup() {
    if (PortalTheme.count() == 0) {
      themeList = readThemesFromDisk()
      themeList foreach {
        PortalTheme.insert(_)
      }
    } else {
      try {
        themeList = readThemesFromDatabase()
      } catch {
        case t: Throwable =>
          Logger.error("Error reading Themes from the database.", t)
          throw t
      }
    }

    if (!getDefaultTheme.isDefined) {
      throw ConfigurationException("No default theme could be found!")
    }
  }

  /**
   * Updates the themes in memory by reading them from the database
   */
  def update() {
    themeList = readThemesFromDatabase()
  }

  def readThemesFromDatabase(): Seq[PortalTheme] = PortalTheme.find(MongoDBObject()).toSeq

  def readThemesFromDisk(): Seq[PortalTheme] = {
    try {
      loadThemesYaml()
    } catch {
      case ex: Throwable => {
        Logger.error("Error updating themes from YAML descriptor")
        throw new RuntimeException("Error updating themes from YAML descriptor", ex)
      }
    }
  }

  def hasSingleTheme: Boolean = themeList.length == 1

  def hasTheme(themeName: String): Boolean = !themeList.filter(theme => theme.name == themeName).isEmpty

  def getDefaultTheme = themeList.filter(_.name == current.configuration.getString("themes.defaultTheme").getOrElse("default")).headOption

  def getByThemeName(name: String) = {
    val theme = themeList.filter(_.name.equalsIgnoreCase(name))
    if (!theme.isEmpty) theme.head
    else getDefaultTheme.get
  }

  def getByDomain(domain: String): PortalTheme = {
    if (Play.isDev) {
      startup()
    }
    if (hasSingleTheme) getDefaultTheme.get
    else {
      // fetch by longest matching subdomain
      themeList.foldLeft(getDefaultTheme.get) {
        (r: PortalTheme, c: PortalTheme) => {
          val rMatches = r.subdomain != None && domain.startsWith(r.subdomain.get)
          val cMatches = c.subdomain != None && domain.startsWith(c.subdomain.get)
          val rLonger = r.subdomain.get.length() > c.subdomain.get.length()

          if (rMatches && cMatches && rLonger) r
          else if (rMatches && cMatches && !rLonger) c
          else if (rMatches && !cMatches) r
          else if (cMatches && !rMatches) c
          else r // default
        }
      }
    }
  }

  // todo: no longer YAML, so rebuild
  private[util] def loadThemesYaml(): Seq[PortalTheme] = {

    val themeFiles = new File(current.path + "/conf").list().filter(_.endsWith("_themes.yml"))

    if (themeFiles.length == 0) {
      Logger("CultureHub").error("No themes configuration YML file could be found in conf/");
      // this probably won't work, but you get the idea...
      System.exit(1)
    }

    PortalTheme.removeAll()

    //    val themes = for (theme: PortalTheme <- themeFiles.flatMap(f => YamlLoader.load[List[PortalTheme]](f))) yield {
    //      theme.copy(localiseQueryKeys = if (theme.localiseQueryKeys == null) defaultQueryKeys else defaultQueryKeys ++ theme.localiseQueryKeys)
    //    }

    val themes = List(PortalTheme(
      name = "default",
      subdomain = Some("default"),
      defaultLanguage = "en",
      solrSelectUrl = "http://localhost:8983/solr",
      cacheUrl = "http://localhost:8983/services/image?",
      emailTarget = EmailTarget(
        adminTo = "servers@delving.eu",
        exceptionTo = "servers@delving.eu",
        feedbackTo = "servers@delving.eu",
        registerTo = "servers@delving.eu",
        systemFrom = "noreply@delving.eu",
        feedbackFrom = "noreply@delving.eu"
      ),
      localiseQueryKeys = List("dc.title", "dc.creator"),
      hiddenQueryFilter = Some(""),
      homePage = None,
      facets = Some("delving_recordType:metadata.searchfield.recordType,europeana_provider:metadata.searchfield.dataprovider,delving_creator:metadata.searchfield.creator,delving_hasDigitalObject:metadata.searchfield.hasDigitalObject"),
      sortFields = Some("delving_hasDigitalObject"),
      apiWsKey = false

    ))

    themes
  }
}


