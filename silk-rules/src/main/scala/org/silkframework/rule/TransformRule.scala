package org.silkframework.rule

import org.silkframework.dataset.TypedProperty
import org.silkframework.entity._
import org.silkframework.rule.input.{Input, PathInput, TransformInput}
import org.silkframework.rule.plugins.transformer.combine.ConcatTransformer
import org.silkframework.rule.plugins.transformer.normalize.UrlEncodeTransformer
import org.silkframework.rule.plugins.transformer.value.{ConstantTransformer, ConstantUriTransformer, EmptyValueTransformer}
import org.silkframework.runtime.serialization._
import org.silkframework.runtime.validation.ValidationException
import org.silkframework.util._

import scala.language.implicitConversions
import scala.xml.{Node, Null}

/**
  * A transformation rule.
  * A transformations rule generates property values from based on an arbitrary operator tree consisting of property paths and transformations.
  * Sub classes are defined for special cases, such as direct mappings.
  */
sealed trait TransformRule {

  /** The name of this rule. */
  def id: Identifier

  /** The input operator tree. */
  def operator: Input

  /** The target property URI. */
  def target: Option[MappingTarget]

  /** String representation of rule type */
  def typeString: String

  def childRules: Seq[TransformRule] = Seq.empty

  /**
    * Generates the transformed values.
    *
    * @param entity The source entity.
    * @return The transformed values.
    * @throws ValidationException If a value failed to be transformed or a generated value doesn't match the target type.
    */
  def apply(entity: Entity): Seq[String] = {
    val values = operator(entity)
    // Validate values
    for {
      valueType <- target.map(_.valueType) if valueType != AutoDetectValueType
      value <- values
    } {
      if(!valueType.validate(value)) {
        throw new ValidationException(s"Value '$value' is not a valid ${valueType.label}")
      }
    }
    values
  }

  /**
    * Collects all paths in this rule.
    */
  def paths: Seq[Path] = {
    def collectPaths(param: Input): Seq[Path] = param match {
      case p: PathInput if p.path.operators.isEmpty => Seq()
      case p: PathInput => Seq(p.path)
      case p: TransformInput => p.inputs.flatMap(collectPaths)
    }

    collectPaths(operator).distinct
  }
}

case class MappingTarget(propertyUri: Uri, valueType: ValueType = AutoDetectValueType) {

  override def toString: String = {
    if(valueType == AutoDetectValueType)
      propertyUri.toString
    else
      s"$propertyUri (${valueType.label})"
  }

}

object MappingTarget {

  implicit object MappingTargetFormat extends XmlFormat[MappingTarget] {

    import XmlSerialization._

    /**
      * Deserializes a value.
      */
    override def read(value: Node)(implicit readContext: ReadContext): MappingTarget = {
      val uri = (value \ "@uri").text.trim
      val valueTypeNode = (value \ "ValueType").head
      MappingTarget(Uri.parse(uri, readContext.prefixes), fromXml[ValueType](valueTypeNode))
    }

    /**
      * Serializes a value.
      */
    override def write(value: MappingTarget)(implicit writeContext: WriteContext[Node]): Node = {
      <MappingTarget uri={value.propertyUri.uri}>
        {toXml[ValueType](value.valueType)}
      </MappingTarget>
    }
  }

  implicit def toTypedProperty(mt: MappingTarget): TypedProperty = TypedProperty(mt.propertyUri.uri, mt.valueType)

}

/**
  * A direct mapping between two properties.
  *
  * @param id             The name of this mapping. For direct mappings usually just the property that is mapped.
  * @param sourcePath     The source path
  * @param mappingTarget  The target property
  */
case class DirectMapping(id: Identifier = "sourcePath",
                         sourcePath: Path = Path(Nil),
                         mappingTarget: MappingTarget = MappingTarget("http://www.w3.org/2000/01/rdf-schema#label")) extends TransformRule {

  override val operator = PathInput(id, sourcePath)

  override val target = Some(mappingTarget)

  override val typeString = "Direct"
}

/**
  * Assigns a new URI to each mapped entity.
  *
  * @param id      The name of this mapping
  * @param pattern A template pattern for generating the URIs based on the entity properties
  */
case class UriMapping(id: Identifier = "uri", pattern: String = "http://example.org/{ID}") extends TransformRule {

  override val operator = {
    val inputs =
      for ((str, i) <- pattern.split("[\\{\\}]").toList.zipWithIndex) yield {
        if (i % 2 == 0)
          TransformInput("constant" + i, ConstantTransformer(str))
        else
          TransformInput("encode" + i, UrlEncodeTransformer(), Seq(PathInput("path" + i, Path.parse(str))))
      }
    TransformInput(transformer = ConcatTransformer(""), inputs = inputs)
  }

  override val target = None

  override val typeString = "URI"
}

/**
  * Generates a link to another entity.
  *
  * @param id      The name of this mapping
  * @param pattern A template pattern for generating the URIs based on the entity properties
  */
case class ObjectMapping(id: Identifier = "object",
                         pattern: String = "http://example.org/{ID}",
                         mappingTarget: MappingTarget = MappingTarget("http://www.w3.org/2002/07/owl#sameAs", UriValueType)) extends TransformRule {

  override val operator = {
    val inputs =
      for ((str, i) <- pattern.split("[\\{\\}]").toList.zipWithIndex) yield {
        if (i % 2 == 0)
          TransformInput("constant" + i, ConstantTransformer(str))
        else
          TransformInput("encode" + i, UrlEncodeTransformer(), Seq(PathInput("path" + i, Path.parse(str))))
      }
    TransformInput(transformer = ConcatTransformer(""), inputs = inputs)
  }

  override val target = Some(mappingTarget)

  override val typeString = "Object"

}

/**
  * A type mapping, which assigns a type to each entitity.
  *
  * @param id      The name of this mapping
  * @param typeUri The type URI.
  */
case class TypeMapping(id: Identifier = "type", typeUri: Uri = "http://www.w3.org/2002/07/owl#Thing") extends TransformRule {

  override val operator = TransformInput("generateType", ConstantUriTransformer(typeUri))

  override val target = Some(MappingTarget("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", UriValueType))

  override val typeString = "Type"

}

/**
  * A complex mapping, which generates property values from based on an arbitrary operator tree consisting of property paths and transformations.
  *
  * @param id       The name of this mapping
  * @param operator The input operator tree
  * @param target   The target property URI
  */
case class ComplexMapping(id: Identifier = "mapping", operator: Input, target: Option[MappingTarget] = None) extends TransformRule {

  override val typeString = "Complex"

}

/**
  * A hierarchical mapping.
  *
  * Generates child entities that are connected to the parent entity using the targetProperty.
  * The properties of the child entities are mapped by the child mappings.
  *
  * @param id The name of this mapping.
  * @param relativePath The relative input path to locate the child entities in the source.
  * @param targetProperty The property that is used to attach the child entities.
  * @param childRules The child rules.
  */
case class HierarchicalMapping(id: Identifier = "mapping", relativePath: Path = Path(Nil), targetProperty: Option[Uri] = Some("hasChild"),
                               override val childRules: Seq[TransformRule]) extends TransformRule {

  override val typeString = "Hierarchical"

  override val operator = {
    targetProperty match {
      case Some(prop) =>
        childRules.find (_.isInstanceOf[UriMapping] ) match {
          case Some (rule) => rule.operator
          case None => PathInput (path = relativePath)
        }
      case None =>
        TransformInput(transformer = EmptyValueTransformer())
    }
  }

  override val target = targetProperty.map(MappingTarget(_, UriValueType))

}

/**
  * Creates new transform rules.
  */
object TransformRule {

  /**
    * XML serialization format.
    */
  implicit object TransformRuleFormat extends XmlFormat[TransformRule] {

    import XmlSerialization._

    def read(node: Node)(implicit readContext: ReadContext): TransformRule = {
      ValidatingXMLReader.validate(node, "org/silkframework/LinkSpecificationLanguage.xsd")

      node.label match {
        case "HierarchicalMapping" => readHierarchicalMapping(node)
        case "TransformRule" => readTransformRule(node)
      }
    }

    private def readHierarchicalMapping(node: Node)(implicit readContext: ReadContext): HierarchicalMapping = {
      HierarchicalMapping(
        id = (node \ "@name").text,
        relativePath = Path.parse((node \ "@relativePath").text),
        targetProperty = (node \ "@targetProperty").headOption.map(_.text).filter(_.nonEmpty).map(Uri(_)),
        childRules = (node \ "Children" \ "_").map(read)
      )
    }

    private def readTransformRule(node: Node)(implicit readContext: ReadContext): TransformRule = {
      // First test new target serialization, else old one
      val target = (node \ "MappingTarget").headOption.
        map(tp => Some(fromXml[MappingTarget](tp))).
        getOrElse {
          val targetProperty = (node \ "@targetProperty").text
          if (targetProperty.isEmpty) {
            None
          } else {
            Some(MappingTarget(Uri.parse(targetProperty, readContext.prefixes)))
          }
        }
      val complex =
        ComplexMapping(
          id = (node \ "@name").text,
          operator = fromXml[Input]((node \ "_").head),
          target = target
        )
      simplify(complex)
    }

    def write(value: TransformRule)(implicit writeContext: WriteContext[Node]): Node = {
      value match {
        case HierarchicalMapping(name, relativePath, targetProperty, childRules) =>
          <HierarchicalMapping name={name} relativePath={relativePath.serialize} targetProperty={targetProperty.map(_.uri).getOrElse("")} >
            <Children>{childRules.map(write)}</Children>
          </HierarchicalMapping>
        case _ =>
          // At the moment, all other types are serialized generically
          <TransformRule name={value.id}>
            {toXml(value.operator)}{value.target.map(toXml[MappingTarget]).getOrElse(Null)}
          </TransformRule>
      }
    }
  }

  /**
    * Tries to express a complex mapping as a basic mapping, such as a direct mapping.
    */
  def simplify(complexMapping: ComplexMapping): TransformRule = complexMapping match {
    // Direct Mapping
    case ComplexMapping(id, PathInput(_, path), Some(target)) =>
      DirectMapping(id, path, target)
    // URI Mapping
    case ComplexMapping(id, TransformInput(_, ConcatTransformer(""), inputs), None) if isPattern(inputs) =>
      UriMapping(id, buildPattern(inputs))
    // Object Mapping
    case ComplexMapping(id, TransformInput(_, ConcatTransformer(""), inputs), Some(target)) if isPattern(inputs) && target.valueType == UriValueType =>
      ObjectMapping(id, buildPattern(inputs), target)
    // Type Mapping
    case ComplexMapping(id, TransformInput(_, ConstantTransformer(typeUri), Nil),
    Some(MappingTarget(Uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), _))) =>
      TypeMapping(id, typeUri)
    // Type Mapping (old style, to be removed)
    case ComplexMapping(id, TransformInput(_, ConstantUriTransformer(typeUri), Nil),
    Some(MappingTarget(Uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), _))) =>
      TypeMapping(id, typeUri)
    // Complex Mapping
    case _ => complexMapping
  }

  private def isPattern(inputs: Seq[Input]) = {
    inputs.forall {
      case PathInput(id, path) => true
      case TransformInput(id, UrlEncodeTransformer(_), Seq(PathInput(_, path))) => true
      case TransformInput(id, ConstantTransformer(constant), Nil) => true
      case _ => false
    }
  }

  private def buildPattern(inputs: Seq[Input]) = {
    inputs.map {
      case PathInput(id, path) => "{" + path.serializeSimplified() + "}"
      case TransformInput(id, UrlEncodeTransformer(_), Seq(PathInput(_, path))) => "{" + path.serializeSimplified() + "}"
      case TransformInput(id, ConstantTransformer(constant), Nil) => constant
    }.mkString("")
  }
}
