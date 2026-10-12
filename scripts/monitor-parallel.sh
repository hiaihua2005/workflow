#!/bin/bash
# 监控并行 agent 文件活动：每 45s 轮询 mtime，连续 8 轮（约 6 分钟）无变化则报告停止
cd /home/z/my-project
FILES="workflow_lowcode/frontend/src/views/logicflow/components/PropertyPanel.vue
workflow_lowcode/frontend/src/views/logicflow/utils/dsl.ts
workflow_lowcode/frontend/src/views/logicflow/config/nodeMeta.ts
workflow_lowcode/backend/src/main/java/com/workflow/engine/logicflow/engine/LogicFlowEngine.java
workflow_lowcode/backend/src/main/java/com/workflow/engine/form/bizdata/BizDataSupport.java
workflow_lowcode/backend/src/main/java/com/workflow/engine/logicflow/validator/LogicFlowDslValidator.java"
STABLE=0; ROUND=0
while [ $STABLE -lt 8 ]; do
  ROUND=$((ROUND+1)); CHANGED=0
  for f in $FILES; do
    [ -f "$f" ] || continue
    M=$(stat -c '%Y' "$f")
    NOW=$(date +%s)
    AGE=$((NOW-M))
    if [ $AGE -lt 100 ]; then CHANGED=1; echo "R$ROUND ACTIVE: $f (${AGE}s ago)"; fi
  done
  if [ $CHANGED -eq 1 ]; then STABLE=0; else STABLE=$((STABLE+1)); fi
  sleep 45
done
echo "PARALLEL_AGENT_IDLE: ${STABLE} rounds x 45s with no edits"
