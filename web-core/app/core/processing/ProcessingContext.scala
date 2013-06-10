package core.processing

import core.collection.{ OrganizationCollectionMetadata, Collection }
import eu.delving.schema.SchemaVersion
import org.joda.time.{ DateTimeZone, DateTime }

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */
case class ProcessingContext(
    collection: Collection with OrganizationCollectionMetadata,
    targetSchemas: Seq[ProcessingSchema],
    sourceNamespaces: Map[String, String],
    renderingSchema: Option[SchemaVersion],
    indexingSchema: Option[SchemaVersion],
    startProcessing: DateTime = new DateTime(DateTimeZone.UTC)) {

  val targetSchemasString = targetSchemas.map(_.prefix).mkString(", ")

}