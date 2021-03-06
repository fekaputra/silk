package org.silkframework.plugins.dataset.csv

import org.silkframework.dataset._
import org.silkframework.runtime.plugin.{Param, Plugin}
import org.silkframework.runtime.resource.{WritableResource, Resource}

import scala.io.Codec

@Plugin(
  id = "csv",
  label = "CSV file",
  description =
      """Retrieves all entities from a csv file."""
)
case class CsvDataset
(
  @Param("File name inside the resources directory. In the Workbench, this is the '(projectDir)/resources' directory.")
    file: WritableResource,
  @Param("Comma-separated list of URL-encoded properties. If not provided, the list of properties is read from the first line.")
    properties: String = "",
  @Param("The character that is used to separate values. If not provided, defaults to ',', i.e., comma-separated values. \"\\t\" for specifying tab-separated values, is also supported.")
    separator: String = ",",
  @Param(label = "Array separator", value = "The character that is used to separate the parts of array values. Write \"\\t\" to specify the tab character.")
    arraySeparator: String = "",
  @Param("Character used to quote values.")
    quote: String = "\"",
  @Param(" A URI prefix that should be used for generating schema entities like classes or properties, e.g. http://www4.wiwiss.fu-berlin.de/ontology/")
    prefix: String = "",
  @Param("A pattern used to construct the entity URI. If not provided the prefix + the line number is used. An example of such a pattern is 'urn:zyx:{id}' where *id* is a name of a property.")
    uri: String = "",
  @Param("A regex filter used to match rows from the CSV file. If not set all the rows are used.")
    regexFilter: String = "",
  @Param("The file encoding, e.g., UTF8, ISO-8859-1")
    charset: String = "UTF-8",
  @Param("The number of lines to skip in the beginning, e.g. copyright, meta information etc.")
    linesToSkip: Int = 0,
  @Param("The maximum characters per column. If there are more characters found, the parser will fail.")
    maxCharsPerColumn: Int = 128000,
  @Param("If set to true then the parser will ignore lines that have syntax errors or do not have to correct number of fields according to the current config.")
    ignoreBadLines: Boolean = false,
  @Param(label = "Quote escape character",
    value = "Escape character to be used inside quotes, used to escape the quote character. It must also be used to escape itself, e.g. by doubling it, e.g. \"\". If left empty, it defaults to quote.")
  quoteEscapeCharacter: String = "\"") extends Dataset with DatasetPluginAutoConfigurable[CsvDataset] with WritableResourceDataset with CsvDatasetTrait with ResourceBasedDataset {

  override def source: DataSource = csvSource

  override def linkSink: LinkSink = new CsvLinkSink(file, csvSettings)

  override def entitySink: EntitySink = new CsvEntitySink(file, csvSettings)

  private def csvSource = new CsvSource(file, csvSettings, properties, prefix, uri, regexFilter, codec,
    skipLinesBeginning = linesToSkip, ignoreBadLines = ignoreBadLines)

  /**
    * returns an auto-configured version of this plugin
    */
  override def autoConfigured: CsvDataset = {
    val autoConfig = csvSource.autoConfigure()
    this.copy(
      separator = if (autoConfig.detectedSeparator == "\t") "\\t" else autoConfig.detectedSeparator,
      charset = autoConfig.codecName,
      linesToSkip = autoConfig.linesToSkip.getOrElse(linesToSkip)
    )
  }

  override def replaceWritableResource(writableResource: WritableResource): WritableResourceDataset = {
    this.copy(file = writableResource)
  }
}