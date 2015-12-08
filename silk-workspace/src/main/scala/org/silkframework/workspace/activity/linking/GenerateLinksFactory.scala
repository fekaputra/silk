package org.silkframework.workspace.activity.linking

import org.silkframework.config.{LinkSpecification, RuntimeConfig}
import org.silkframework.dataset.Dataset
import org.silkframework.entity.Link
import org.silkframework.execution.GenerateLinks
import org.silkframework.runtime.activity.Activity
import org.silkframework.workspace.Task
import org.silkframework.workspace.activity.TaskActivityFactory

case class GenerateLinksFactory(
  useFileCache: Boolean = false,
  partitionSize: Int = 300,
  generateLinksWithEntities: Boolean = true,
  writeOutputs: Boolean = true) extends TaskActivityFactory[LinkSpecification, GenerateLinks] {

  def apply(task: Task[LinkSpecification]): Activity[Seq[Link]] = {
    Activity.regenerating {
      var linksSpec = task.data
      if(!writeOutputs)
        linksSpec = linksSpec.copy(outputs = Seq.empty)

      GenerateLinks.fromSources(
        datasets = task.project.tasks[Dataset].map(_.data),
        linkSpec = linksSpec,
        runtimeConfig = RuntimeConfig(useFileCache = useFileCache, partitionSize = partitionSize, generateLinksWithEntities = generateLinksWithEntities)
      )
    }
  }
}