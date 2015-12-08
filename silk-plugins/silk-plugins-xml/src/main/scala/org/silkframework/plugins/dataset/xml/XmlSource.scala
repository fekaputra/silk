package org.silkframework.plugins.dataset.xml

import java.net.URLEncoder
import java.util.logging.{Level, Logger}

import org.silkframework.dataset.DataSource
import org.silkframework.entity._
import org.silkframework.entity.rdf.{SparqlRestriction, SparqlEntitySchema}
import org.silkframework.runtime.resource.Resource

import scala.xml.{Node, NodeSeq, XML}

class XmlSource(file: Resource, basePath: String, uriPattern: String) extends DataSource {

  private val logger = Logger.getLogger(getClass.getName)

  private val uriRegex = "\\{([^\\}]+)\\}".r

  override def retrieveSparqlPaths(restriction: SparqlRestriction, depth: Int, limit: Option[Int]): Traversable[(Path, Double)] = {
    // At the moment we just generate paths from the first xml node that is found
    val xml = loadXmlNodes().head
    for (path <- XmlParser.collectPaths(xml)) yield {
      (Path(restriction.variable, path.tail.toList), 1.0)
    }
  }

  override def retrieveSparqlEntities(entityDesc: SparqlEntitySchema, entities: Seq[String] = Seq.empty): Traversable[Entity] = {

    logger.log(Level.FINE, "Retrieving data from XML.")

    new Entities(loadXmlNodes(), entityDesc)

  }

  private def loadXmlNodes() = {
    // Load XML
    val xml = XML.load(file.load)
    // Resolve the base path
    if (basePath.isEmpty) {
      // If the base path is empty, we read all direct children of the root element
      xml \ "_"
    } else {
      // As it may not be clear whether the base path must include the root element, we accept both
      val path =
        if (basePath.startsWith("/" + xml.label))
          basePath.stripPrefix("/" + xml.label)
        else
          basePath
      // Move to base path
      evaluateXPath(xml, path)
    }
  }

  private def evaluateXPath(node: Node, path: String): NodeSeq = {
    var currentNode: NodeSeq = node
    for (label <- path.stripPrefix("/").split('/') if !label.isEmpty) {
      currentNode = currentNode \ label
    }
    currentNode
  }

  private class Entities(xml: NodeSeq, entityDesc: SparqlEntitySchema) extends Traversable[Entity] {
    def foreach[U](f: Entity => U) {
      // Enumerate entities
      for ((node, index) <- xml.zipWithIndex) {
        val uri =
          if (uriPattern.isEmpty)
            node.label + index
          else
            uriRegex.replaceAllIn(uriPattern, m =>
              URLEncoder.encode(evaluateXPath(node, m.group(1)).text, "UTF8")
            )

        val values = for (path <- entityDesc.paths) yield evaluateSilkPath(node, path)
        f(new Entity(uri, values, entityDesc))
      }
    }

    private def evaluateSilkPath(node: NodeSeq, path: Path): Set[String] = {
      var xml = node
      for (op <- path.operators) {
        xml = evaluateOperator(xml, op)
      }
      xml.map(_.text).toSet
    }

    private def evaluateOperator(node: NodeSeq, op: PathOperator): NodeSeq = op match {
      case ForwardOperator(p) => node \ p.uri
      case p @ PropertyFilter(prop, cmp, value) =>
        node.filter(n => p.evaluate("\"" + (n \ prop.uri).text + "\""))
      case _ => throw new UnsupportedOperationException("Unsupported path operator: " + op.getClass.getSimpleName)
    }
  }

}