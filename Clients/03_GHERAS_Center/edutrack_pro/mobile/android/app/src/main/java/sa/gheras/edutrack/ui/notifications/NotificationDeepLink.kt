package sa.gheras.edutrack.ui.notifications

import android.net.Uri
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.local.session.Role

sealed interface NotificationAction {
    data class OpenAssignment(val assignmentId: String) : NotificationAction
    data class OpenAttendance(val roomId: String, val date: String) : NotificationAction
    data class OpenChildHomework(val studentId: String) : NotificationAction
    data class OpenChildAttendance(val studentId: String) : NotificationAction
    data class OpenExternalUrl(val url: String) : NotificationAction
    data class ShowAnnouncement(val title: String, val body: String?, val senderName: String?) : NotificationAction
    data object None : NotificationAction
}

object NotificationDeepLinkResolver {

    fun resolve(notification: NotificationEntity, role: Role?): NotificationAction {
        // 1. Check custom actionUrl if present
        val actionUrl = notification.actionUrl
        if (!actionUrl.isNullOrBlank()) {
            val uri = try { Uri.parse(actionUrl) } catch (_: Exception) { null }
            if (uri != null) {
                if (uri.scheme == "gheras") {
                    when (uri.host) {
                        "assignment" -> {
                            val id = uri.getQueryParameter("id") ?: notification.targetId
                            if (!id.isNullOrBlank()) {
                                return if (role == Role.TEACHER) {
                                    NotificationAction.OpenAssignment(id)
                                } else {
                                    val studentId = uri.getQueryParameter("student_id")
                                    if (!studentId.isNullOrBlank()) {
                                        NotificationAction.OpenChildHomework(studentId)
                                    } else {
                                        NotificationAction.OpenAssignment(id)
                                    }
                                }
                            }
                        }
                        "attendance" -> {
                            val studentId = uri.getQueryParameter("student_id")
                            val roomId = uri.getQueryParameter("room_id")
                            val date = uri.getQueryParameter("date") ?: java.time.LocalDate.now().toString()
                            if (role == Role.GUARDIAN || role == Role.STUDENT) {
                                if (!studentId.isNullOrBlank()) {
                                    return NotificationAction.OpenChildAttendance(studentId)
                                }
                            } else if (role == Role.TEACHER && !roomId.isNullOrBlank()) {
                                return NotificationAction.OpenAttendance(roomId, date)
                            }
                        }
                        "announcement" -> {
                            return NotificationAction.ShowAnnouncement(
                                title = notification.title,
                                body = notification.body,
                                senderName = notification.senderName
                            )
                        }
                    }
                } else if (uri.scheme == "http" || uri.scheme == "https") {
                    return NotificationAction.OpenExternalUrl(actionUrl)
                }
            }
        }

        // 2. Fallback to targetType and targetId
        val targetType = notification.targetType?.lowercase()
        val targetId = notification.targetId

        if (!targetId.isNullOrBlank()) {
            when (targetType) {
                "assignment" -> {
                    return if (role == Role.TEACHER) {
                        NotificationAction.OpenAssignment(targetId)
                    } else {
                        NotificationAction.OpenChildHomework(targetId)
                    }
                }
                "attendance" -> {
                    return if (role == Role.GUARDIAN || role == Role.STUDENT) {
                        NotificationAction.OpenChildAttendance(targetId)
                    } else {
                        NotificationAction.OpenAttendance(targetId, java.time.LocalDate.now().toString())
                    }
                }
            }
        }

        // 3. Fallback to kind
        val kind = notification.kind.lowercase()
        if (kind.contains("announcement") || kind.contains("broadcast")) {
            return NotificationAction.ShowAnnouncement(
                title = notification.title,
                body = notification.body,
                senderName = notification.senderName
            )
        }

        return NotificationAction.None
    }
}
