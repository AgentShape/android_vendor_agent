# agent 项目的产品挂接点（ADR-0014）。
# 目标产品在自己的产品 makefile 中 inherit 本文件。

PRODUCT_PACKAGES += \
    AgentApp \
    AgentFrameworkOverlay \
    AgentFramework \
    agentd

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/agent/sepolicy/private
