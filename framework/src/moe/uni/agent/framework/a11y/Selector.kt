package moe.uni.agent.framework.a11y

import org.json.JSONArray
import org.json.JSONObject

/** 选择器（ADR-0024）：所有字段都要满足；index 在全部匹配中取第几个。 */
object Selector {
    private val EXACT = setOf(NodeFields.TEXT, NodeFields.DESC, NodeFields.HINT, NodeFields.LABEL, NodeFields.ID, NodeFields.CLS)
    private val CONTAINS = setOf(NodeFields.TEXT, NodeFields.DESC, NodeFields.LABEL).associateBy { it + SelectorKeys.CONTAINS_SUFFIX }
    private val FLAGS = setOf(
        NodeFields.CLICKABLE, NodeFields.LONG_CLICKABLE, NodeFields.CHECKABLE, NodeFields.CHECKED,
        NodeFields.EDITABLE, NodeFields.FOCUSED, NodeFields.SELECTED, NodeFields.ENABLED,
    )

    /** 在节点 JSON 中挑出匹配的；选择器含未知字段时报 IllegalArgumentException。 */
    fun select(nodes: List<JSONObject>, selector: JSONObject): List<JSONObject> {
        val matches = nodes.filter { matches(it, selector) }
        return if (selector.has(SelectorKeys.INDEX)) listOfNotNull(matches.getOrNull(selector.getInt(SelectorKeys.INDEX))) else matches
    }

    private fun matches(node: JSONObject, selector: JSONObject): Boolean =
        selector.keys().asSequence().filter { it != SelectorKeys.INDEX }.all { key ->
            when (key) {
                in EXACT -> node.optString(key) == selector.getString(key)
                in CONTAINS -> node.optString(CONTAINS.getValue(key)).contains(selector.getString(key))
                NodeFields.BOUNDS -> ints(node.getJSONArray(key)) == ints(selector.getJSONArray(key))
                NodeFields.SCROLLABLE -> (node.getJSONArray(key).length() > 0) == selector.getBoolean(key)
                in FLAGS -> node.optBoolean(key) == selector.getBoolean(key)
                else -> throw IllegalArgumentException("选择器不支持字段 $key")
            }
        }

    private fun ints(array: JSONArray) = (0 until array.length()).map { array.getInt(it) }
}
