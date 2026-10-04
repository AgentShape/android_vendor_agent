package moe.uni.agent.tools

import moe.uni.agent.llm.Responses
import org.json.JSONArray
import org.json.JSONObject

/** 工具参数的 JSON Schema。模型会填满所有可选参数，工具不要靠"有没有传某个参数"来切换行为。 */
class Schema {
    private val properties = JSONObject()
    private val required = JSONArray()

    /** 必填参数的名字，ToolRegistry 执行前检查。 */
    val requiredNames: List<String> get() = (0 until required.length()).map { required.getString(it) }

    fun string(name: String, description: String, required: Boolean = false) = param(name, TYPE_STRING, description, required)
    fun integer(name: String, description: String, required: Boolean = false) = param(name, TYPE_INTEGER, description, required)
    fun boolean(name: String, description: String, required: Boolean = false) = param(name, TYPE_BOOLEAN, description, required)
    fun number(name: String, description: String, required: Boolean = false) = param(name, TYPE_NUMBER, description, required)

    private fun param(name: String, type: String, description: String, isRequired: Boolean) {
        properties.put(name, JSONObject().put(TYPE, type).put(DESCRIPTION, description))
        if (isRequired) required.put(name)
    }

    fun toJson(name: String, description: String): JSONObject = JSONObject()
        .put(Responses.TYPE, Responses.TYPE_FUNCTION)
        .put(Responses.NAME, name)
        .put(DESCRIPTION, description)
        .put(PARAMETERS, JSONObject().put(TYPE, TYPE_OBJECT).put(PROPERTIES, properties).put(REQUIRED, required))

    companion object {
        private const val TYPE = "type"
        private const val DESCRIPTION = "description"
        private const val PARAMETERS = "parameters"
        private const val PROPERTIES = "properties"
        private const val REQUIRED = "required"
        private const val TYPE_OBJECT = "object"
        private const val TYPE_STRING = "string"
        private const val TYPE_INTEGER = "integer"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_NUMBER = "number"
    }
}
