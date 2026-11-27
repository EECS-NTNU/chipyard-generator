package presolve

import freechips.rocketchip.util.ElaborationArtefacts
import org.json4s.{JObject, JValue}
import org.json4s.jackson.JsonMethods.{compact, render}

object ElaborationParameters {
  private val extension = "presolve-parameters.json"
  private val components = scala.collection.mutable.Map.empty[String, JObject]

  /** Records settings used by a component during Chipyard system elaboration. */
  def report(component: String, fields: (String, JValue)*): Unit = {
    require(component.nonEmpty, "A Presolve parameter report requires a component name")
    require(fields.map(_._1).distinct.size == fields.size, s"Duplicate parameter names for '$component'")
    val parameters = JObject(fields.sortBy(_._1).toList)
    if (!ElaborationArtefacts.contains(extension)) {
      components.clear()
      ElaborationArtefacts.add(extension, compact(render(JObject(components.toList.sortBy(_._1)))))
    }
    require(components.get(component).forall(_ == parameters), s"Conflicting parameter reports for '$component'")
    components.update(component, parameters)
  }
}
