/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.silkframework.dataset

import java.util.logging.Logger

import org.silkframework.config.Task.TaskFormat
import org.silkframework.config.{MetaData, Task, TaskSpec}
import org.silkframework.entity._
import org.silkframework.runtime.serialization.{ReadContext, WriteContext, XmlFormat, XmlSerialization}
import org.silkframework.util.{Identifier, SampleUtil, Uri}

import scala.language.implicitConversions
import scala.xml.Node

/**
  * A dataset of entities.
  *
  * @param uriProperty Setting this URI will generate an additional property for each entity.
                       The additional property contains the URI of each entity.
  */
case class DatasetSpec(plugin: Dataset, uriProperty: Option[Uri] = None) extends TaskSpec with Dataset {

  private val log = Logger.getLogger(DatasetSpec.getClass.getName)

  def source: DataSource = DatasetSpec.DataSourceWrapper(plugin.source, this)

  def entitySink: EntitySink = DatasetSpec.EntitySinkWrapper(plugin.entitySink, this)

  def linkSink: LinkSink = DatasetSpec.LinkSinkWrapper(plugin.linkSink, this)

  /** Datasets don't define input schemata, because any data can be written to them. */
  override lazy val inputSchemataOpt: Option[Seq[EntitySchema]] = None

  /** Datasets don't have a static EntitySchema. It is defined by the following task. */
  override lazy val outputSchemaOpt: Option[EntitySchema] = None

  override def toString: String = DatasetSpec.toString

}

case class DatasetTask(id: Identifier, data: DatasetSpec, metaData: MetaData = MetaData.empty) extends Task[DatasetSpec]

object DatasetSpec {

  implicit def toTransformTask(task: Task[DatasetSpec]): DatasetTask = DatasetTask(task.id, task.data, task.metaData)

  def empty = {
    new DatasetSpec(EmptyDataset)
  }

  case class DataSourceWrapper(source: DataSource, datasetSpec: DatasetSpec) extends DataSource {

    override def retrieveTypes(limit: Option[Int] = None): Traversable[(String, Double)] = {
      source.retrieveTypes(limit)
    }

    override def retrievePaths(typeUri: Uri, depth: Int = 1, limit: Option[Int] = None): IndexedSeq[Path] = {
      source.retrievePaths(typeUri, depth, limit)
    }

    override def sampleEntities(entityDesc: EntitySchema,
                                size: Int,
                                filterOpt: Option[Entity => Boolean]): Seq[Entity] = {
      sampleEntities(entityDesc, size, filterOpt)
    }

    /**
      * Retrieves entities from this source which satisfy a specific entity schema.
      *
      * @param entitySchema The entity schema
      * @param limit        Limits the maximum number of retrieved entities
      * @return A Traversable over the entities. The evaluation of the Traversable may be non-strict.
      */
    override def retrieve(entitySchema: EntitySchema, limit: Option[Int]): Traversable[Entity] = {
      val adaptedSchema = adaptSchema(entitySchema)
      val entities = source.retrieve(adaptedSchema, limit)
      adaptUris(entities, adaptedSchema)
    }

    /**
      * Retrieves a list of entities from this source.
      *
      * @param entitySchema The entity schema
      * @param entities     The URIs of the entities to be retrieved.
      * @return A Traversable over the entities. The evaluation of the Traversable may be non-strict.
      */
    override def retrieveByUri(entitySchema: EntitySchema, entities: Seq[Uri]): Seq[Entity] = {
      val adaptedSchema = adaptSchema(entitySchema)
      val retrievedEntities = source.retrieveByUri(adaptedSchema, entities)
      adaptUris(retrievedEntities, adaptedSchema).toSeq
    }

    /**
      * Adds the URI property to the schema.
      */
    private def adaptSchema(entitySchema: EntitySchema): EntitySchema = {
      datasetSpec.uriProperty match {
        case Some(property) =>
          entitySchema.copy(typedPaths = entitySchema.typedPaths :+ TypedPath(Path.parse(property.uri), UriValueType, isAttribute = false))
        case None =>
          entitySchema
      }
    }

    /**
      * Rewrites the entity URIs if an URI property has been specified.
      */
    private def adaptUris(entities: Traversable[Entity], entitySchema: EntitySchema): Traversable[Entity] = {
      datasetSpec.uriProperty match {
        case Some(property) =>
          val uriIndex = entitySchema.pathIndex(Path.parse(property.uri))
          for (entity <- entities) yield {
            new Entity(
              uri = entity.evaluate(uriIndex).headOption.getOrElse(entity.uri),
              values = entity.values,
              desc = entity.desc
            )
          }
        case None =>
          entities
      }
    }
  }

  case class EntitySinkWrapper(entitySink: EntitySink, datasetSpec: DatasetSpec) extends EntitySink {

    private val log = Logger.getLogger(DatasetSpec.getClass.getName)

    private var entityCount: Int = 0

    private var isOpen = false

    /**
      * Initializes this writer.
      */
    override def openTable(typeUri: Uri, properties: Seq[TypedProperty]) {
      if (isOpen) {
        entitySink.close()
        isOpen = false
      }

      val uriTypedProperty =
        for(property <- datasetSpec.uriProperty.toIndexedSeq) yield {
          TypedProperty(property.uri, UriValueType, isBackwardProperty = false)
        }

      entitySink.openTable(typeUri, uriTypedProperty ++ properties)
      entityCount = 0
      isOpen = true
    }

    override def writeEntity(subject: String, values: Seq[Seq[String]]) {
      require(isOpen, "Output must be opened before writing statements to it")
      datasetSpec.uriProperty match {
        case Some(property) =>
          entitySink.writeEntity(subject, Seq(subject) +: values)
        case None =>
          entitySink.writeEntity(subject, values)
      }

      entityCount += 1
    }

    /**
      * Closes the current table.
      */
    override def closeTable() {
      if (entitySink != null) entitySink.closeTable()
      isOpen = false
    }

    /**
      * Closes this writer.
      */
    override def close() {
      if (entitySink != null) entitySink.close()
      isOpen = false
      log.info(s"Wrote $entityCount entities.")
    }

    /**
      * Makes sure that the next write will start from an empty dataset.
      */
    override def clear(): Unit = entitySink.clear()
  }

  case class LinkSinkWrapper(linkSink: LinkSink, datasetSpec: DatasetSpec) extends LinkSink {

    private val log = Logger.getLogger(DatasetSpec.getClass.getName)

    private var linkCount: Int = 0

    private var isOpen = false

    override def init(): Unit = {
      if (isOpen) {
        linkSink.close()
        isOpen = false
      }
      linkSink.init()
      isOpen = true
    }

    /**
      * Writes a new link to this writer.
      */
    override def writeLink(link: Link, predicateUri: String) {
      //require(isOpen, "Output must be opened before writing statements to it")

      linkSink.writeLink(link, predicateUri)
      linkCount += 1
    }

    /**
      * Closes this writer.
      */
    override def close() {
      if (linkSink != null) linkSink.close()
      isOpen = false
      log.info(s"Wrote $linkCount links.")
    }

    /**
      * Makes sure that the next write will start from an empty dataset.
      */
    override def clear(): Unit = linkSink.clear()
  }

  /**
    * XML serialization format.
    */
  implicit object DatasetSpecFormat extends XmlFormat[DatasetSpec] {

    override def tagNames: Set[String] = Set("Dataset")

    def read(node: Node)(implicit readContext: ReadContext): DatasetSpec = {
      implicit val prefixes = readContext.prefixes
      implicit val resources = readContext.resources

      // Check if the data source still uses the old outdated XML format
      if (node.label == "DataSource" || node.label == "Output") {
        // Read old format
        val id = (node \ "@id").text
        new DatasetSpec(
          plugin = Dataset((node \ "@type").text, XmlSerialization.deserializeParameters(node))
        )
      } else {
        // Read new format
        val id = (node \ "@id").text
        val uriProperty = (node \ "@uriProperty").headOption.map(_.text).filter(_.trim.nonEmpty).map(Uri(_))
        // In outdated formats the plugin parameters are nested inside a DatasetPlugin node
        val sourceNode = (node \ "DatasetPlugin").headOption.getOrElse(node)
        new DatasetSpec(
          plugin = Dataset((sourceNode \ "@type").text, XmlSerialization.deserializeParameters(sourceNode)),
          uriProperty = uriProperty
        )
      }
    }

    def write(value: DatasetSpec)(implicit writeContext: WriteContext[Node]): Node = {
      value.plugin match {
        case Dataset(pluginDesc, params) =>
          <Dataset type={pluginDesc.id} uriProperty={value.uriProperty.map(_.uri).getOrElse("")}>
            {XmlSerialization.serializeParameter(params)}
          </Dataset>
      }
    }
  }

  implicit object DatasetTaskXmlFormat extends XmlFormat[DatasetTask] {
    override def read(value: Node)(implicit readContext: ReadContext): DatasetTask = {
      new TaskFormat[DatasetSpec].read(value)
    }

    override def write(value: DatasetTask)(implicit writeContext: WriteContext[Node]): Node = {
      new TaskFormat[DatasetSpec].write(value)
    }
  }

}