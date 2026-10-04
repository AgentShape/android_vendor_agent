package moe.uni.agent.framework.control

/**
 * ScreenResult 中 JSON 的字段名和取值（ADR-0030 第 3、4 条）。AgentApp 的工具说明和 exec_prelude.js 按这些名字读取，
 * 它们不能引用本文件，改动时一起改。
 */
object ScreenFields {
    const val DISPLAY = "display"
    const val MAIN = "main"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val TOP = "top"
    const val IME = "ime"
    const val FOCUSED = "focused"

    /** query 的结果：屏幕信息的字段加上匹配的节点。 */
    const val NODES = "nodes"
}

/** 动作做了什么。 */
object ActionFields {
    const val ELEMENT = "element"
    const val METHOD = "method"
    const val ACTION = "action"
    const val POINT = "point"
    const val COUNT = "count"
    const val LONG = "long"
    const val LENGTH = "length"
    const val START = "start"
    const val END = "end"
    const val DIRECTION = "direction"
    const val PAGES = "pages"
    const val SCROLLED = "scrolled"
    const val AREA = "area"
    const val SWIPES = "swipes"
    const val DISTANCE = "distance"
    const val FROM = "from"
    const val TO = "to"
    const val DURATION_MS = "duration_ms"
    const val KEY = "key"
    const val POSITION = "position"
    const val REPLACED = "replaced"
    const val CURSOR = "cursor"
    const val LAUNCHED = "launched"
    const val VISIBLE = "visible"
    const val TASK = "task"
    const val FROM_DISPLAY = "from_display"
    const val TO_DISPLAY = "to_display"
    const val MOVED = "moved"

    // method 的取值
    const val METHOD_ACCESSIBILITY = "accessibility"
    const val METHOD_TOUCH = "touch"
    const val METHOD_SWIPE = "swipe"
}

/** list() 的 data。 */
object ListFields {
    const val SCREENS = "screens"
    const val TASKS = "tasks"
    const val APPS = "apps"
    const val DISPLAY = "display"
    const val MAIN = "main"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val AWAKE = "awake"
    const val LOCKED = "locked"
    const val TASK = "task"
    const val COMPONENT = "component"
    const val VISIBLE = "visible"
    const val LABEL = "label"
    const val PACKAGE = "package"
}

/** events 中的一项。 */
object EventFields {
    const val TYPE = "type"
    const val DISPLAY = "display"
    const val COMPONENT = "component"
    const val TASK = "task"
    const val TASK_DISPLAY = "task_display"
    const val RESULT = "result"

    // type 的取值
    const val SECURE_WINDOW = "secure_window"
    const val LAUNCH_BLOCKED = "launch_blocked"

    // secure_window 的 result 取值
    const val MOVED_TO_MAIN = "moved_to_main"
    const val MOVED_BY_SYSTEM = "moved_by_system"
    const val MOVE_FAILED = "move_failed"
    const val NOT_MOVED = "not_moved"
    const val NO_TASK = "no_task"
}

/**
 * 等待用的事件（ADR-0034 第 4 条），经 IAgentEvents 推给 AgentApp；exec_prelude.js 按这些名字读取。
 * 通知事件的其余字段与 notification_list 的单条相同（device/Notifications.kt）。
 */
object WaitEventFields {
    const val TYPE = "type"
    const val DISPLAY = "display"
    /** 是否主屏幕：脚本里虚拟屏幕写作 -1，按它区分屏幕。 */
    const val MAIN = "main"
    const val PACKAGE = "package"
    const val CLS = "cls"
    const val TEXT = "text"
    /** pane 出现为 true，消失为 false。 */
    const val SHOWN = "shown"
    /** 通知的变化：posted、updated、removed。 */
    const val CHANGE = "change"
    /** 通知被移除的原因（NotificationListenerService 的 REASON_*）。 */
    const val REASON = "reason"

    // type 的取值
    /** 屏幕有变化（无障碍事件或新帧），只用来唤醒等待，不带内容。 */
    const val CHANGED = "changed"
    const val WINDOW = "window"
    const val PANE = "pane"
    const val TOAST = "toast"
    const val ANNOUNCEMENT = "announcement"
    const val NOTIFICATION = "notification"

    // change 的取值
    const val POSTED = "posted"
    const val UPDATED = "updated"
    const val REMOVED = "removed"
}
