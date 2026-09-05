package pm.views

import pm.domain.{Task, User}

/** Shared task create/edit form fragments (used by the project board and the
  * global /tasks/new page).
  */
object TaskForms {
  def statusOptions: List[(String, String)] = List(
    Task.StatusTodo -> "To-do",
    Task.StatusInProgress -> "In progress",
    Task.StatusDone -> "Done"
  )

  def priorityOptions: List[(String, String)] = List(
    Task.PriorityLow -> "Low",
    Task.PriorityMedium -> "Medium",
    Task.PriorityHigh -> "High"
  )

  /** Create-task form. projectOptions empty => fixed project via hidden field. */
  def createTaskForm(
      csrf: String,
      action: String,
      projectOptions: List[(Long, String)],
      fixedProject: Option[Long],
      assignees: List[(Long, String)],
      errors: Map[String, String],
      values: Map[String, String],
      submitLabel: String = "Create task"
  ): String = {
    val projectField =
      if (projectOptions.nonEmpty)
        Ui.selectField(
          "projectId",
          "Project",
          projectOptions.map { case (id, n) => id.toString -> n },
          values.get("projectId").orElse(fixedProject.map(_.toString)),
          errors.get("projectId"),
          required = true,
          includeEmpty = Some("Choose a project…")
        )
      else s"""<input type="hidden" name="projectId" value="${fixedProject.getOrElse(0L)}">"""

    val title = Ui.textField("title", "Title", values.getOrElse("title", ""), errors.get("title"), required = true, placeholder = "Summarise the work in a sentence", maxlength = Some(200))
    val description = Ui.textareaField("description", "Description", values.getOrElse("description", ""), errors.get("description"), rows = 4, maxlength = Some(8000), help = Some("What needs to happen, and why? Optional."))
    val assignee = Ui.selectField("assigneeId", "Assignee", assignees.map { case (id, n) => id.toString -> n }, values.get("assigneeId"), errors.get("assigneeId"), includeEmpty = Some("Unassigned"))
    val status = Ui.selectField("status", "Status", statusOptions, Some(values.getOrElse("status", Task.StatusTodo)), errors.get("status"))
    val priority = Ui.selectField("priority", "Priority", priorityOptions, Some(values.getOrElse("priority", Task.PriorityMedium)), errors.get("priority"))
    val due = Ui.dateField("dueDate", "Due date", values.get("dueDate"), errors.get("dueDate"))
    s"""<form method="post" action="$action" novalidate>
         ${Ui.csrf(csrf)}
         <div class="two-col-form">
           $projectField$title
           $assignee$status
           $priority$due
         </div>
         $description
         ${Ui.submit("createTask", submitLabel, "btn-brand", Some("Creating…"))}
       </form>"""
  }

  /** Task edit form on the task detail page (status/priority included). */
  def editTaskForm(
      csrf: String,
      taskId: Long,
      assignees: List[(Long, String)],
      errors: Map[String, String],
      values: Map[String, String]
  ): String = {
    val v = (k: String) => values.getOrElse(k, "")
    s"""<form method="post" action="/tasks/$taskId/edit" novalidate>
         ${Ui.csrf(csrf)}
         ${Ui.textField("title", "Title", v("title"), errors.get("title"), required = true, maxlength = Some(200))}
         ${Ui.textareaField("description", "Description", v("description"), errors.get("description"), rows = 6, maxlength = Some(8000))}
         <div class="two-col-form">
           ${Ui.selectField("status", "Status", statusOptions, Some(v("status")), errors.get("status"))}
           ${Ui.selectField("priority", "Priority", priorityOptions, Some(v("priority")), errors.get("priority"))}
           ${Ui.selectField("assigneeId", "Assignee", assignees.map { case (id, n) => id.toString -> n }, Some(v("assigneeId")), errors.get("assigneeId"), includeEmpty = Some("Unassigned"))}
           ${Ui.dateField("dueDate", "Due date", Some(v("dueDate")), errors.get("dueDate"))}
         </div>
         ${Ui.submit("editTask", "Save changes", "btn-brand", Some("Saving…"))}
       </form>"""
  }
}
