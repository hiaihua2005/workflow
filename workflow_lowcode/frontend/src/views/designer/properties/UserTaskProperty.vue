<template>
  <div class="user-task-property">
    <!-- 顶部（tab 外）：节点名称 + 审批类型 -->
    <el-form label-width="7em" label-position="left" size="small" :disabled="readOnly" class="top-form">
      <el-form-item label="节点名称">
        <el-input v-model="config.name" placeholder="如：部门经理审批" @change="updateBpmnName" />
      </el-form-item>
    </el-form>

    <div class="section-title">审批类型</div>
    <el-radio-group
      :model-value="ui.approvalType"
      :disabled="readOnly"
      class="approval-type-group"
      @update:model-value="onApprovalTypeChange"
    >
      <el-radio value="artificial">人工审批</el-radio>
      <el-radio value="auto_pass">自动通过</el-radio>
      <el-radio value="auto_reject">自动拒绝</el-radio>
    </el-radio-group>

    <!-- 自动审批类型无需配置审批人：提示 + 禁用 tabs 内容 -->
    <el-alert
      v-if="isAutoType"
      class="auto-tip"
      type="info"
      :closable="false"
      show-icon
      title="自动审批类型无需配置审批人"
    />

    <el-alert
      v-else-if="!ui.approval.type"
      class="auto-tip"
      type="warning"
      :closable="false"
      show-icon
      title="请先选择审批人类型"
    />

    <el-tabs v-model="activeTab" class="user-task-property-tabs" :class="{ 'is-auto': isAutoType }">
      <!-- 审批人设置 -->
      <el-tab-pane label="审批人设置" name="assignee">
        <div class="tab-inner">
          <AssigneeSelector
            v-model="ui.approval.type"
            :user-ids="ui.approval.userIds"
            :role-codes="ui.approval.roleCodes"
            :allow-adjust="ui.assignee.allowInitiatorAdjust"
            :form-user-field="ui.approval.formUserField"
            :external-resolver="ui.approval.externalResolver"
            :external-params="ui.approval.externalParams"
            kind="approver"
            :disabled="readOnly"
            @update:user-ids="(ids: number[]) => (ui.approval.userIds = ids)"
            @update:role-codes="(codes: string[]) => (ui.approval.roleCodes = codes)"
            @update:allow-adjust="(v: boolean) => (ui.assignee.allowInitiatorAdjust = v)"
            @update:form-user-field="(v: string) => (ui.approval.formUserField = v)"
            @update:external-resolver="(v: string) => (ui.approval.externalResolver = v)"
            @update:external-params="(p: Record<string, unknown>) => (ui.approval.externalParams = p)"
          />

          <template v-if="ui.approval.type === 'expression'">
            <div class="section-title">流程表达式</div>
            <ExpressionEditor
              v-model="ui.approval.expression"
              syntax="uel"
              multiline
              :rows="2"
              :variables="uelVars"
              placeholder="如：${initiator.deptManager}"
              :disabled="readOnly"
              @change="saveConfig"
            />
          </template>

          <div class="section-title">找不到审批人时</div>
          <el-radio-group
            v-model="ui.assignee.noAssigneePolicy"
            class="v-radio-group"
            :disabled="readOnly"
            @change="saveConfig"
          >
            <el-radio value="auto_pass">自动通过</el-radio>
            <el-radio value="block">禁止提交流程</el-radio>
            <el-radio value="to_admin">转交审批管理员</el-radio>
            <el-radio value="to_user">转交指定用户</el-radio>
            <el-radio value="skip">跳过此审批节点</el-radio>
            <el-radio value="supervisor">由发起人主管代审批</el-radio>
          </el-radio-group>
          <ApproverPicker
            v-if="ui.assignee.noAssigneePolicy === 'to_user'"
            v-model="ui.assignee.toUserIds"
            :multiple="false"
            :disabled="readOnly"
            placeholder="选择转交用户"
            class="to-user-picker"
            @change="saveConfig"
          />

          <div class="section-title">多人审批时采用审批方式</div>
          <el-radio-group
            v-model="ui.approval.multiMode"
            class="v-radio-group"
            :disabled="readOnly"
            @change="saveConfig"
          >
            <el-radio value="countersign">会签（需要所有人同意）</el-radio>
            <el-radio value="or_sign">或签（仅需其中一人同意）</el-radio>
            <el-radio value="sequential">依次审批</el-radio>
            <el-radio value="vote_sign">票签（按权重投票）</el-radio>
          </el-radio-group>

          <!-- 会签通过比例：同意比例达到阈值即通过（100 = 全员通过） -->
          <div v-if="ui.approval.multiMode === 'countersign'" class="checkbox-col sub-items">
            <div class="ratio-row">
              <span class="ratio-label">同意比例达到</span>
              <el-input-number
                v-model="ui.approval.passRatio"
                :min="1"
                :max="99"
                :step="10"
                :disabled="readOnly"
                size="small"
                @change="saveConfig"
              />
              <span class="ratio-label">% 即通过</span>
            </div>
            <div class="field-hint">默认 100% = 全员同意；低于 100% 时达到比例即通过，剩余审批自动通过</div>
          </div>

          <!-- 票签：权重投票配置 -->
          <template v-if="ui.approval.multiMode === 'vote_sign'">
            <div class="checkbox-col sub-items">
              <div class="ratio-row">
                <span class="ratio-label">同意权重达到</span>
                <el-input-number
                  v-model="ui.approval.votePassRatio"
                  :min="1"
                  :max="100"
                  :step="10"
                  :disabled="readOnly"
                  size="small"
                  @change="saveConfig"
                />
                <span class="ratio-label">% 即通过</span>
              </div>
              <div class="field-hint">每票默认权重 1；权重达标即通过，其余票自动作废。可在下方为指定人员设置不同权重</div>
            </div>
            <div v-if="ui.approval.type === 'user' && ui.approval.userIds.length > 0" class="checkbox-col sub-items">
              <div class="section-title">投票权重（按人设置，缺省 1）</div>
              <div v-for="uid in ui.approval.userIds" :key="uid" class="ratio-row weight-row">
                <span class="weight-user">{{ userLabel(uid) }}</span>
                <el-input-number
                  :model-value="ui.approval.weights[String(uid)] ?? 1"
                  :min="1"
                  :max="99"
                  :disabled="readOnly"
                  size="small"
                  @change="(v: number | undefined) => setVoteWeight(String(uid), v)"
                />
              </div>
            </div>
          </template>

          <div class="section-title">消息通知</div>
          <el-checkbox v-model="ui.notifySms" :disabled="readOnly" @change="saveConfig">
            发送短信给审批人
          </el-checkbox>
        </div>
      </el-tab-pane>

      <!-- 高级设置 -->
      <el-tab-pane label="高级设置" name="advanced">
        <div class="tab-inner">
          <div class="section-title">审批人可进行的操作</div>
          <!-- 主选项横向一行：通过/拒绝固定开启且只读 -->
          <div class="checkbox-row">
            <el-checkbox :model-value="true" disabled>通过</el-checkbox>
            <el-checkbox :model-value="true" disabled>拒绝</el-checkbox>
            <el-checkbox v-model="ui.operations.allowTransfer" :disabled="readOnly">转派</el-checkbox>
            <el-checkbox v-model="ui.operations.allowReturn" :disabled="readOnly">退回</el-checkbox>
            <el-checkbox v-model="ui.operations.allowAddSign" :disabled="readOnly">加签</el-checkbox>
          </div>
          <!-- 二级选项：纵向展示在主选项下方；退回模式二选一（单选，Task 79b） -->
          <template v-if="ui.operations.allowReturn">
            <div class="checkbox-col sub-items">
              <el-radio-group
                class="return-mode-group"
                :model-value="returnMode"
                :disabled="readOnly"
                @update:model-value="setReturnMode($event as string)"
              >
                <el-radio value="restartFromHere">退回后，从此节点开始审批，已经通过的节点无需再次审批</el-radio>
                <el-radio value="chooseStartNode">退回后，由审批人选择重审的起始节点</el-radio>
              </el-radio-group>
            </div>
          </template>
          <template v-if="ui.operations.allowAddSign">
            <div class="checkbox-col sub-items">
              <el-checkbox v-model="ui.returnOptions.mustAddSign" :disabled="readOnly">
                此节点必须加签
              </el-checkbox>
            </div>
          </template>

          <div class="section-title">
            <FormLabelTip tip="开启后，审批人必须填写审批意见">审批意见必填</FormLabelTip>
          </div>
          <div class="switch-row">
            <el-switch v-model="ui.commentRequired" :disabled="readOnly" />
            <span class="switch-label">审批意见必填</span>
          </div>
          <!-- 意见必填范围单选（截图②）：默认拒绝/退回必填 -->
          <template v-if="ui.commentRequired">
            <div class="inline-radio-group sub-items">
              <el-radio-group
                v-model="ui.commentRequiredScope"
                :disabled="readOnly"
                @change="saveConfig"
              >
                <el-radio value="REJECT_RETURN">拒绝/退回必填</el-radio>
                <el-radio value="ALL">全部操作必填</el-radio>
              </el-radio-group>
            </div>
          </template>

          <div class="section-title">禁止撤销/撤回</div>
          <div class="switch-row">
            <el-switch v-model="ui.blockRecall" :disabled="readOnly" />
            <span class="switch-label">流程到达此节点后禁止撤销/撤回</span>
          </div>

          <div class="section-title">
            <FormLabelTip tip="支持审批超时自动提醒、转派、通过、拒绝">超时处理</FormLabelTip>
          </div>
          <div class="switch-row">
            <el-switch v-model="ui.timeout.enabled" :disabled="readOnly" />
            <span class="switch-label">超时处理</span>
          </div>
          <!-- 超时规则组（截图③）：添加超时规则 → ProcessTimeoutRuleDialog，同一规则组提醒可多条、转派 1 条、通过/拒绝互斥 -->
          <template v-if="ui.timeout.enabled">
            <ul v-if="ui.timeoutRules.length > 0" class="timeout-rule-list">
              <li v-for="rule in ui.timeoutRules" :key="rule.id" class="timeout-rule-item">
                <span class="rule-tag" :class="`rule-${rule.action}`">{{ RULE_ACTION_TEXT[rule.action] }}</span>
                <span class="rule-text">
                  超过 {{ rule.duration }} {{ RULE_UNIT_TEXT[rule.unit] }}未处理{{ RULE_ACTION_TEXT[rule.action] }}<template v-if="rule.action === 'remind' && rule.repeat">（重复提醒）</template>
                </span>
                <el-button link type="primary" size="small" :disabled="readOnly" @click="openTimeoutDialog(rule.id)">编辑</el-button>
                <el-button link type="danger" size="small" :disabled="readOnly" @click="removeTimeoutRule(rule.id)">删除</el-button>
              </li>
            </ul>
            <el-button size="small" :disabled="readOnly" class="rule-add-btn" @click="openTimeoutDialog(null)">
              添加超时规则
            </el-button>
            <ProcessTimeoutRuleDialog
              v-model:visible="timeoutDialogVisible"
              :rules="ui.timeoutRules"
              :edit-id="timeoutEditId"
              @confirm="onTimeoutRuleConfirm"
            />
          </template>

          <div class="section-title">
            <FormLabelTip tip="开启后，同一审批人不用重复审批">审批人去重</FormLabelTip>
          </div>
          <div class="switch-row">
            <el-switch v-model="ui.dedup.enabled" :disabled="readOnly" />
            <span class="switch-label">审批人去重</span>
          </div>
          <div v-if="ui.dedup.enabled" class="checkbox-col sub-items">
            <!-- 去重命中口径（截图①）：上一节点已同意 / 前面任意节点已同意 -->
            <el-radio-group
              v-model="ui.dedup.mode"
              class="return-mode-group"
              :disabled="readOnly"
              @change="saveConfig"
            >
              <el-radio value="CONSECUTIVE">上一节点此审批人已同意时，此节点自动通过</el-radio>
              <el-radio value="FIRST">前面任意节点此审批人已同意时，此节点自动通过</el-radio>
            </el-radio-group>
            <div class="inline-row dedup-row">
              <el-checkbox
                v-model="ui.dedup.skipSameAsInitiator"
                :disabled="readOnly"
                @change="saveConfig"
              >
                审批人与
              </el-checkbox>
              <el-select
                :model-value="'发起人'"
                size="small"
                disabled
                style="width: 90px"
                class="dedup-select"
              />
              <span class="inline-label">相同时，此节点自动跳过</span>
            </div>
          </div>

          <div class="section-title">手写签名</div>
          <div class="switch-row">
            <el-switch v-model="ui.signature.enabled" :disabled="readOnly" />
            <span class="switch-label">手写签名</span>
          </div>
          <template v-if="ui.signature.enabled">
            <!-- 子选项横向排列（Task 79c）：复用主选项 checkbox-row 横排 + sub-items 缩进 -->
            <div class="checkbox-row sub-items">
              <el-checkbox v-model="ui.signature.useLast" :disabled="readOnly">默认使用上次签名</el-checkbox>
              <el-checkbox v-model="ui.signature.allowUpload" :disabled="readOnly">支持上传签名图片</el-checkbox>
              <el-checkbox v-model="ui.signature.required" :disabled="readOnly">必须签名</el-checkbox>
            </div>
          </template>

          <div class="section-title">任务优先级</div>
          <el-radio-group v-model="ui.priority" :disabled="readOnly" @change="saveConfig">
            <el-radio-button :value="50">普通</el-radio-button>
            <el-radio-button :value="75">重要</el-radio-button>
            <el-radio-button :value="100">加急</el-radio-button>
          </el-radio-group>

          <div class="section-title">
            <FormLabelTip tip="任务创建后 N 小时到达截止时间（dueDate），用于待办超期提醒；0 = 不设置">
              办理时限（小时）
            </FormLabelTip>
          </div>
          <div class="inline-row">
            <el-input-number v-model="ui.dueHours" :min="0" :max="8760" :disabled="readOnly" @change="saveConfig" />
            <span class="inline-label">0 = 不设置</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- 字段权限设置 -->
      <el-tab-pane label="字段权限设置" name="form">
        <FormPropertyTab :read-only="readOnly" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, watch } from 'vue'
import { useDesignerStore, type NodeConfigData, type ProcessTimeoutRule } from '@/stores/designerStore'
import { getModeler } from '../utils/bpmnModeler'
import { ApproverPicker } from '@/components/business'
import { getUserBatch } from '@/api/user'
import ExpressionEditor from '@/components/expression/ExpressionEditor.vue'
import { UEL_FLOW_VARS } from '@/components/expression/uelVars'
import FormPropertyTab from './FormPropertyTab.vue'
import AssigneeSelector from './shared/AssigneeSelector.vue'
import ProcessTimeoutRuleDialog from './ProcessTimeoutRuleDialog.vue'
import FormLabelTip from './shared/FormLabelTip.vue'

const uelVars = UEL_FLOW_VARS

defineProps<{ readOnly?: boolean }>()

/** approval.type 可选值（取自冻结 schema） */
type ApprovalTypeValue = NonNullable<NonNullable<NodeConfigData['approval']>['type']>

const designerStore = useDesignerStore()

const activeTab = ref('assignee')

// 加载标志：loadConfig 期间禁止 watch 触发 saveConfig，避免把新节点值写回旧节点
let isLoading = false

const config = reactive({
  name: ''
})

/** 面板 UI 态（保存时映射为冻结 schema 的 nodeConfigs 结构） */
const ui = reactive({
  /** 审批类型：artificial / auto_pass / auto_reject */
  approvalType: 'artificial' as 'artificial' | 'auto_pass' | 'auto_reject',
  approval: {
    type: '' as string,
    userIds: [] as number[],
    roleCodes: [] as string[],
    expression: '',
    formUserField: '',
    externalResolver: '',
    externalParams: {} as Record<string, unknown>,
    multiMode: '' as 'countersign' | 'or_sign' | 'sequential' | 'vote_sign' | '',
    /** 会签通过比例（1-99 生效，100=全员通过） */
    passRatio: 100,
    /** 票签通过权重比例（1-100，缺省 50） */
    votePassRatio: 50,
    /** 票签权重表（userId → 权重，仅 type=user 且 multiMode=vote_sign 时可配） */
    weights: {} as Record<string, number>,
  },
  assignee: {
    allowInitiatorAdjust: false,
    noAssigneePolicy: '' as '' | 'auto_pass' | 'block' | 'to_admin' | 'to_user' | 'skip' | 'supervisor',
    toUserIds: [] as number[],
  },
  operations: {
    allowRefuse: true,
    allowReturn: true,
    allowTransfer: true,
    allowAddSign: false,
    allowDelegate: false,
  },
  returnOptions: {
    restartFromHere: false,
    chooseStartNode: false,
    mustAddSign: false,
  },
  commentRequired: false,
  /** 意见必填范围：REJECT_RETURN=拒绝/退回必填（新开启默认）；ALL=全部操作必填（存量无 scope 兼容显示） */
  commentRequiredScope: 'REJECT_RETURN' as 'REJECT_RETURN' | 'ALL',
  blockRecall: false,
  timeout: {
    enabled: false,
  },
  /** 节点级超时规则组（对齐流程级 timeoutRules；legacy 单规则 duration/action 加载时迁移进来） */
  timeoutRules: [] as ProcessTimeoutRule[],
  dedup: {
    enabled: false,
    skipSameAsInitiator: false,
    /** 去重命中口径：CONSECUTIVE=上一节点已同意（默认）；FIRST=前面任意节点已同意 */
    mode: 'CONSECUTIVE' as 'CONSECUTIVE' | 'FIRST',
  },
  signature: {
    enabled: false,
    useLast: false,
    allowUpload: false,
    required: false,
  },
  notifySms: false,
  /** 任务优先级（普通 50 / 重要 75 / 加急 100） */
  priority: 50,
  /** 办理时限（小时，0 = 不设置 → dueDate） */
  dueHours: 0,
})

/** 自动通过/自动拒绝：无需配置审批人，tabs 内容整体禁用 */
const isAutoType = computed(() => ui.approvalType === 'auto_pass' || ui.approvalType === 'auto_reject')

/** 退回模式单选（Task 79b）：restartFromHere/chooseStartNode 互斥，映射到存储里的两个布尔键（格式不变，兼容旧数据） */
const returnMode = computed(() =>
  ui.returnOptions.restartFromHere
    ? 'restartFromHere'
    : ui.returnOptions.chooseStartNode
      ? 'chooseStartNode'
      : '',
)
function setReturnMode(v: string) {
  ui.returnOptions.restartFromHere = v === 'restartFromHere'
  ui.returnOptions.chooseStartNode = v === 'chooseStartNode'
}

// ========== 超时规则组（截图③：添加超时规则）==========
/** 超时规则对话框状态（editId=null 新增） */
const timeoutDialogVisible = ref(false)
const timeoutEditId = ref<string | null>(null)

const RULE_ACTION_TEXT: Record<ProcessTimeoutRule['action'], string> = {
  remind: '超时提醒',
  transfer: '超时转派',
  pass: '超时通过',
  refuse: '超时拒绝',
}
const RULE_UNIT_TEXT: Record<ProcessTimeoutRule['unit'], string> = {
  minute: '分钟',
  hour: '小时',
  day: '天',
}

/** legacy 节点级单规则（duration小时+action）→ 规则组一条（escalate 对应规则组的 transfer） */
function legacyTimeoutToRule(duration: number | undefined, action: string | undefined): ProcessTimeoutRule {
  const mapped = action === 'escalate' ? 'transfer' : action === 'pass' ? 'pass' : action === 'refuse' ? 'refuse' : 'remind'
  return {
    id: `rule-legacy-${Date.now()}`,
    action: mapped,
    duration: duration && duration > 0 ? duration : 24,
    unit: 'hour',
    repeat: false,
    notifyAssignee: true,
    notifyAdmin: false,
    notifyUserIds: [],
    sms: true,
  }
}

function openTimeoutDialog(editId: string | null) {
  timeoutEditId.value = editId
  timeoutDialogVisible.value = true
}

function removeTimeoutRule(ruleId: string) {
  ui.timeoutRules = ui.timeoutRules.filter((r) => r.id !== ruleId)
}

function onTimeoutRuleConfirm(rule: ProcessTimeoutRule) {
  const idx = ui.timeoutRules.findIndex((r) => r.id === rule.id)
  if (idx >= 0) {
    ui.timeoutRules.splice(idx, 1, rule)
  } else {
    ui.timeoutRules.push(rule)
  }
}

onMounted(() => {
  loadConfig()
})

// 切换同类型节点时重新加载配置（组件不重建，onMounted 不触发）
watch(() => designerStore.selectedNodeId, (newId, oldId) => {
  if (newId && newId !== oldId) {
    loadConfig()
  }
})

function onApprovalTypeChange(value: string) {
  ui.approvalType = value as typeof ui.approvalType
  saveConfig()
}

// ---- 票签权重（multiMode=vote_sign）----

/** 审批人姓名缓存（userId → nickname），用于权重表展示 */
const userNames = ref<Record<string, string>>({})

function userLabel(uid: number | string): string {
  const key = String(uid)
  return userNames.value[key] || key
}

function setVoteWeight(uid: string, value: number | undefined) {
  const w = Math.max(1, Math.round(value ?? 1))
  ui.approval.weights = { ...ui.approval.weights, [uid]: w }
  saveConfig()
}

// 审批人列表变化时拉取姓名（type=user）
watch(
  () => ui.approval.userIds,
  async (ids) => {
    const missing = ids.map(String).filter((id) => !userNames.value[id])
    if (missing.length === 0) return
    try {
      const res = await getUserBatch(ids)
      const map: Record<string, string> = {}
      for (const u of res.data) {
        map[String(u.id)] = u.nickname
      }
      userNames.value = { ...userNames.value, ...map }
    } catch {
      // 拉取失败回退显示 ID
    }
  },
  { deep: true },
)

function loadConfig() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return

  isLoading = true

  const bo = element.businessObject
  config.name = bo.name || ''

  // 重置为默认值，避免残留上一节点
  ui.approvalType = 'artificial'
  ui.approval.type = ''
  ui.approval.userIds = []
  ui.approval.roleCodes = []
  ui.approval.expression = ''
  ui.approval.formUserField = ''
  ui.approval.externalResolver = ''
  ui.approval.externalParams = {}
  ui.approval.multiMode = ''
  ui.approval.passRatio = 100
  ui.approval.votePassRatio = 50
  ui.approval.weights = {}
  ui.assignee.allowInitiatorAdjust = false
  ui.assignee.noAssigneePolicy = ''
  ui.assignee.toUserIds = []
  ui.operations.allowRefuse = true
  ui.operations.allowReturn = true
  ui.operations.allowTransfer = true
  ui.operations.allowAddSign = false
  ui.operations.allowDelegate = false
  ui.returnOptions.restartFromHere = false
  ui.returnOptions.chooseStartNode = false
  ui.returnOptions.mustAddSign = false
  ui.commentRequired = false
  ui.commentRequiredScope = 'REJECT_RETURN'
  ui.blockRecall = false
  ui.timeout.enabled = false
  ui.timeoutRules = []
  ui.dedup.enabled = false
  ui.dedup.skipSameAsInitiator = false
  ui.dedup.mode = 'CONSECUTIVE'
  ui.signature.enabled = false
  ui.signature.useLast = false
  ui.signature.allowUpload = false
  ui.signature.required = false
  ui.notifySms = false
  ui.priority = 50
  ui.dueHours = 0

  // 加载已有配置覆盖默认值
  const existing = designerStore.getNodeConfig(designerStore.selectedNodeId!)
  if (existing) {
    if (existing.approvalType) {
      ui.approvalType = existing.approvalType
    }
    if (existing.approval) {
      // 兼容旧配置：user / dept_head / expression 直接映射；发起人自己 initiator_self 恢复为合法选项
      ui.approval.type = existing.approval.type || ''
      ui.approval.userIds = (existing.approval.userIds || []).map((id) => Number(id))
      ui.approval.roleCodes = (existing.approval.roleCodes || []).map((c) => String(c))
      ui.approval.expression = existing.approval.expression || ''
      ui.approval.formUserField = existing.approval.formUserField || ''
      ui.approval.externalResolver = existing.approval.external?.resolver || ''
      ui.approval.externalParams = { ...(existing.approval.external?.params ?? {}) }
      ui.approval.multiMode = existing.approval.multiMode || ''
      ui.approval.passRatio = existing.approval.passRatio ?? 100
      ui.approval.votePassRatio = existing.approval.votePassRatio ?? 50
      ui.approval.weights = { ...(existing.approval.weights ?? {}) }
    }
    if (existing.assigneeOptions) {
      ui.assignee.allowInitiatorAdjust = existing.assigneeOptions.allowInitiatorAdjust ?? false
      ui.assignee.noAssigneePolicy = existing.assigneeOptions.noAssigneePolicy || ''
      ui.assignee.toUserIds = existing.assigneeOptions.toUserId
        ? [Number(existing.assigneeOptions.toUserId)].filter((n) => !Number.isNaN(n))
        : []
    }
    if (existing.operations) {
      // 兼容旧配置：旧 allowReject（驳回）映射到 allowReturn；
      // 拒绝/通过固定开启（UI 只读，allowRefuse 不再从存量配置读取）
      const legacyReject = existing.operations.allowReject
      ui.operations.allowRefuse = true
      ui.operations.allowReturn = existing.operations.allowReturn ?? legacyReject ?? true
      ui.operations.allowTransfer = existing.operations.allowTransfer ?? true
      ui.operations.allowAddSign = existing.operations.allowAddSign ?? false
      ui.operations.allowDelegate = existing.operations.allowDelegate ?? false
    }
    if (existing.returnOptions) {
      ui.returnOptions.restartFromHere = existing.returnOptions.restartFromHere ?? false
      ui.returnOptions.chooseStartNode = existing.returnOptions.chooseStartNode ?? false
      ui.returnOptions.mustAddSign = existing.returnOptions.mustAddSign ?? false
    }
    ui.commentRequired = existing.commentRequired ?? false
    // 意见必填范围：显式 scope 优先；存量开了必填但无 scope 按 ALL（全部操作必填，与旧后端口径一致）
    ui.commentRequiredScope =
      existing.commentRequiredScope ?? (existing.commentRequired ? 'ALL' : 'REJECT_RETURN')
    ui.blockRecall = existing.blockRecall ?? false
    if (existing.timeout) {
      ui.timeout.enabled = existing.timeout.enabled ?? false
      if (existing.timeout.rules && existing.timeout.rules.length > 0) {
        ui.timeoutRules = existing.timeout.rules.map((r) => ({ ...r }))
      } else if (ui.timeout.enabled) {
        // legacy 单规则迁移（duration小时+action）→ 规则组一条
        ui.timeoutRules = [legacyTimeoutToRule(existing.timeout.duration, existing.timeout.action)]
      }
    }
    if (existing.dedup) {
      ui.dedup.enabled = existing.dedup.enabled ?? false
      ui.dedup.skipSameAsInitiator = existing.dedup.skipSameAsInitiator ?? false
      ui.dedup.mode = existing.dedup.mode ?? 'CONSECUTIVE'
    }
    if (existing.signature) {
      ui.signature.enabled = existing.signature.enabled ?? false
      ui.signature.useLast = existing.signature.useLast ?? false
      ui.signature.allowUpload = existing.signature.allowUpload ?? false
      ui.signature.required = existing.signature.required ?? false
    }
    if (existing.notify) {
      ui.notifySms = existing.notify.sms ?? false
    }
    ui.priority = existing.priority ?? 50
    ui.dueHours = existing.dueHours ?? 0
  }

  // nextTick 后恢复，确保 watch 不捕获加载期间的变更
  setTimeout(() => { isLoading = false }, 0)
}

function updateBpmnName() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const modeling = (modeler as any).get('modeling')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (element) {
    modeling.updateProperties(element, { name: config.name })
  }
}

function saveConfig() {
  if (!designerStore.selectedNodeId) return
  if (isLoading) return

  // 在已有配置上合并，保留 form 等未在本面板编辑的块与旧 operations 键
  const existing = designerStore.getNodeConfig(designerStore.selectedNodeId!) || {}

  const nodeConfig: NodeConfigData = {
    ...existing,
    basic: {
      ...(existing.basic || {}),
      name: config.name
    },
    taskRole: 'approver',
    approvalType: ui.approvalType,
    approval: {
      type: (ui.approval.type || undefined) as ApprovalTypeValue | undefined,
      userIds: ui.approval.type === 'user' && ui.approval.userIds.length > 0 ? ui.approval.userIds : undefined,
      roleCodes: ui.approval.type === 'role' && ui.approval.roleCodes.length > 0 ? [...ui.approval.roleCodes] : undefined,
      expression: ui.approval.type === 'expression' ? ui.approval.expression || undefined : undefined,
      formUserField:
        ui.approval.type === 'form_user' ? ui.approval.formUserField || undefined : undefined,
      external:
        ui.approval.type === 'external' && ui.approval.externalResolver
          ? {
              resolver: ui.approval.externalResolver,
              // 函数参数值表（空对象不落盘，保持历史配置形状）
              ...(Object.keys(ui.approval.externalParams ?? {}).length > 0
                ? { params: { ...ui.approval.externalParams } }
                : {}),
            }
          : undefined,
      multiMode: ui.approval.multiMode,
      // 会签通过比例（100 = 全员通过，不落盘保持旧配置形状）
      ...(ui.approval.multiMode === 'countersign' && ui.approval.passRatio > 1
        ? { passRatio: ui.approval.passRatio }
        : {}),
      // 票签：权重比例 + 按人权重表（仅保留当前已选用户，剔除已移除人员）
      ...(ui.approval.multiMode === 'vote_sign'
        ? {
            votePassRatio: ui.approval.votePassRatio,
            ...(Object.keys(ui.approval.weights).length > 0
              ? {
                  weights: Object.fromEntries(
                    Object.entries(ui.approval.weights).filter(([uid, w]) =>
                      ui.approval.userIds.map(String).includes(uid) && w > 0),
                  ),
                }
              : {}),
          }
        : {}),
    },
    assigneeOptions: {
      allowInitiatorAdjust: ui.assignee.allowInitiatorAdjust,
      noAssigneePolicy: ui.assignee.noAssigneePolicy,
      toUserId:
        ui.assignee.noAssigneePolicy === 'to_user' && ui.assignee.toUserIds.length > 0
          ? String(ui.assignee.toUserIds[0])
          : null,
    },
    operations: {
      ...(existing.operations || {}),
      allowPass: true,
      // 通过/拒绝固定开启（UI 只读，不随配置关闭）
      allowRefuse: true,
      allowReturn: ui.operations.allowReturn,
      allowAddSign: ui.operations.allowAddSign,
      allowTransfer: ui.operations.allowTransfer,
      allowDelegate: ui.operations.allowDelegate,
    },
    returnOptions: {
      restartFromHere: ui.returnOptions.restartFromHere,
      chooseStartNode: ui.returnOptions.chooseStartNode,
      mustAddSign: ui.returnOptions.mustAddSign,
    },
    commentRequired: ui.commentRequired,
    commentRequiredScope: ui.commentRequired ? ui.commentRequiredScope : undefined,
    blockRecall: ui.blockRecall,
    timeout: {
      enabled: ui.timeout.enabled,
      // legacy 兼容字段：取首条规则换算（规则组非空时后端以 rules 为准）
      duration: ui.timeoutRules[0]
        ? Math.max(1, Math.round((ui.timeoutRules[0].duration * (ui.timeoutRules[0].unit === 'minute' ? 1 : ui.timeoutRules[0].unit === 'hour' ? 60 : 1440)) / 60))
        : 24,
      action: ui.timeoutRules[0]
        ? (ui.timeoutRules[0].action === 'transfer' ? 'escalate' : ui.timeoutRules[0].action)
        : 'remind',
      rules: ui.timeoutRules.length > 0 ? ui.timeoutRules.map((r) => ({ ...r })) : undefined,
    },
    dedup: {
      enabled: ui.dedup.enabled,
      skipSameAsInitiator: ui.dedup.skipSameAsInitiator,
      mode: ui.dedup.mode,
    },
    signature: {
      enabled: ui.signature.enabled,
      useLast: ui.signature.useLast,
      allowUpload: ui.signature.allowUpload,
      required: ui.signature.required,
    },
    notify: {
      sms: ui.notifySms,
    },
    priority: ui.priority,
    dueHours: ui.dueHours > 0 ? ui.dueHours : undefined,
  }

  designerStore.setNodeConfig(designerStore.selectedNodeId, nodeConfig)
}

watch(ui, () => {
  saveConfig()
}, { deep: true })
</script>

<style scoped>
.user-task-property {
  display: flex;
  flex-direction: column;
}

.top-form {
  margin-bottom: 4px;
}

/* 分区标题：左竖条 + 加粗（截图风格『▎标题』） */
.section-title {
  display: flex;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #1f2437);
  /* padding 10px + 左竖条 3px = 13px：与白卡片 border 1px + padding 12px 对齐，分组标题文本与表单 label 左缘平齐（Task 81） */
  padding-left: 10px;
  border-left: 3px solid var(--el-color-primary);
  margin: 14px 0 8px;
  line-height: 1.2;
}

.approval-type-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0 12px;
}

.approval-type-group .el-radio {
  margin-right: 0;
}

.auto-tip {
  margin: 8px 0;
}

.auto-tip :deep(.el-alert__title) {
  font-size: 12px;
}

/* 自动审批类型：tabs 内容整体禁用（tab 仍可切换查看） */
.user-task-property-tabs.is-auto :deep(.el-tabs__content) {
  pointer-events: none;
  opacity: 0.55;
}

.user-task-property-tabs {
  padding: 0;
}

.user-task-property-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}

.user-task-property-tabs :deep(.el-tabs__content) {
  overflow-y: auto;
}

.tab-inner {
  padding-bottom: 8px;
}

/* 竖排 radio 组 */
.v-radio-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
}

.v-radio-group .el-radio {
  height: 26px;
  margin-right: 0;
}

.v-radio-group .el-radio :deep(.el-radio__label) {
  font-size: 12px;
}

/* 竖排 checkbox 组 */
.checkbox-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  gap: 2px;
}

/* 主选项横向一行（审批人可进行的操作：通过/拒绝只读 + 可勾选项） */
.checkbox-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  width: 100%;
  gap: 0 14px;
}

.checkbox-row .el-checkbox {
  height: auto;
  margin-right: 0;
}

.checkbox-row .el-checkbox :deep(.el-checkbox__label) {
  font-size: 12px;
  white-space: nowrap;
}

.checkbox-col .el-checkbox {
  height: auto;
  margin-right: 0;
}

.checkbox-col .el-checkbox :deep(.el-checkbox__label) {
  font-size: 12px;
  white-space: normal;
  line-height: 1.35;
}

/* 允许 xx 勾选后显示的子项：缩进 */
.sub-items {
  margin: 4px 0 4px 16px;
  padding-left: 8px;
  border-left: 1px dashed var(--el-border-color-lighter, #eef1fc);
}

/* 退回模式单选（纵向、长文案可换行） */
.return-mode-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  gap: 2px;
}

.return-mode-group .el-radio {
  height: auto;
  margin-right: 0;
  align-items: flex-start;
}

.return-mode-group .el-radio :deep(.el-radio__label) {
  font-size: 12px;
  white-space: normal;
  line-height: 1.35;
}

/* 开关行 */
.switch-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.switch-label {
  font-size: 12px;
  color: var(--el-text-color-regular, #4b5169);
}

/* 开关下方灰色说明（已迁移至 FormLabelTip 悬浮提示） */

/* 行内编辑（超时时长/动作、去重条件） */
.inline-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 6px 0;
}

.inline-label {
  font-size: 12px;
  color: var(--el-text-color-regular, #4b5169);
  white-space: nowrap;
}

.dedup-row {
  flex-wrap: wrap;
}

.dedup-row .el-checkbox :deep(.el-checkbox__label) {
  white-space: nowrap;
}

/* 意见必填范围单选：横排一行（截图②），缩进对齐 sub-items */
.inline-radio-group {
  margin: 4px 0 4px 16px;
  padding-left: 8px;
  border-left: 1px dashed var(--el-border-color-lighter, #eef1fc);
}

.inline-radio-group .el-radio-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0 14px;
}

.inline-radio-group .el-radio {
  height: 24px;
  margin-right: 0;
}

.inline-radio-group .el-radio :deep(.el-radio__label) {
  font-size: 12px;
}

/* 节点级超时规则列表（截图③：添加超时规则） */
.timeout-rule-list {
  list-style: none;
  margin: 4px 0;
  padding: 0;
}

.timeout-rule-item {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 5px 8px;
  margin-bottom: 4px;
  background: var(--el-fill-color-lighter, #f5f7fa);
  border-radius: 4px;
  font-size: 12px;
}

.rule-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  color: #fff;
  white-space: nowrap;
}

.rule-tag.rule-remind {
  background: #f59e0b;
}

.rule-tag.rule-transfer {
  background: #3b82f6;
}

.rule-tag.rule-pass {
  background: #10b981;
}

.rule-tag.rule-refuse {
  background: #ef4444;
}

.rule-text {
  color: var(--el-text-color-regular, #4b5169);
  flex: 1;
  min-width: 0;
}

.rule-add-btn {
  margin-top: 2px;
}

.to-user-picker {
  margin: 8px 0 4px;
}

/* 会签通过比例 / 票签权重行 */
.ratio-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0;
}

.ratio-label {
  font-size: 12px;
  color: var(--el-text-color-regular, #4b5169);
  white-space: nowrap;
}

.field-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary, #8b91ab);
  line-height: 1.4;
}

.weight-row {
  justify-content: space-between;
}

.weight-user {
  font-size: 12px;
  color: var(--el-text-color-primary, #1f2437);
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
