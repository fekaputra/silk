package org.silkframework.runtime.serialization

import org.silkframework.config.Prefixes
import org.silkframework.runtime.resource.{EmptyResourceManager, ResourceManager}

import scala.xml.Node

/**
 * XML serialization format.
 */
trait XmlFormat[T] extends SerializationFormat[T, Node] {
}