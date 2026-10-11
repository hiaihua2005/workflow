/**
 * 关联记录卡片 RelationCard（Task 3-g）——表单设计器「基础组件」分组 vendor rule。
 *
 * 与 signaturePad.js 同构（menu:'main' + input + mask）；差异点：
 * - 只读展示组件：v2 实时模式（来源三级解析：页面数据源绑定 dataSourceId > 全局数据源
 *   globalDataSourceId > 业务表单 sourceFormKey，+ recordIdField）下监听记录 id 实时拉取
 *   源最新整行（数据源模式 dataSourceApi.getData / 表单模式 bizDataApi.detail，返回同形），
 *   多字段摘要 + 详情弹窗 + 刷新；不配置则保持 v1 被动模式（值由关联记录选择/联动规则写入，仅做摘要展示）
 * - props 面板（v4 单一数据源绑定）：title（卡内标题）/ description（说明文字）/
 *   globalDataSourceId（数据源，唯一来源入口）/ recordIdField（记录 id 所在字段）/
 *   displayFields（摘要展示字段多选）/ clickable（是否可点击查看详情）。
 *   v1 遗留的 displayField（被动模式对象取值键）已从面板移除（实时模式下无用，
 *   渲染端 prop 保留兼容存量）；卡片值不落业务列（ColumnConfigDialog 显式跳过），
 *   关联关系由「记录 ID 字段」指向的字段（如 dataPicker id 列）承载。
 *   单源依据：业务表单/审批表单发布时 DataSourceSyncListener 自动生成对应
 *   FORM/WORKFLOW 数据源（存量已核验全覆盖），面板无需再双轨暴露业务表单直连；
 *   sourceFormKey 仅作为渲染端兼容回退保留（不暴露面板），面板打开时由
 *   RelationFieldSelect(migrateFromField='sourceFormKey') 自动迁移为对应 FORM 数据源。
 *   数据源下拉=已启用数据源列表（五类型带类型标注），两个字段下拉=所选源的字段列表
 *   （数据源 metadata / 表单 columnConfig 双通道，未配置或加载失败回退静态常用字段，始终可手填）
 * - 各 prop 的 title 内联中文，不依赖 locale 新增词条（localeProps 找不到 com.RelationCard.props.*
 *   时回退 title；主会话后续如补 locale 词条会自动覆盖）
 *
 * 【接线点（由主会话统一执行，本文件不自行注册）】
 * 1. src/vendor/config/index.js：import relationCard from './rule/relationCard'，并加入 ruleList
 * 2. src/main.ts：import RelationCard from '@/components/business/RelationCard.vue' +
 *    FcDesigner.component('RelationCard', RelationCard)（设计器画布 + 运行时渲染双实例可见）
 * 3. rule.type 'RelationCard' 与组件注册名一致，画布/运行态按该名解析组件
 */
import uniqueId from '@form-create/utils/lib/unique';
import { localeProps } from '../../utils';

const label = '关联记录卡片';
const name = 'RelationCard';

// 静态兜底候选字段：未配置源表单 / 加载失败时回退使用（同时作为 rule.options
// 供测试契约断言 ≥9 项；动态候选由 RelationFieldSelect 按源表单字段列表加载）
const SUMMARY_FIELD_FALLBACK = [
    { value: 'name', label: 'name（名称）' },
    { value: 'title', label: 'title（标题）' },
    { value: 'status', label: 'status（状态）' },
    { value: 'owner', label: 'owner（负责人）' },
    { value: 'phone', label: 'phone（电话）' },
    { value: 'code', label: 'code（编码）' },
    { value: 'label', label: 'label（标签）' },
    { value: 'value', label: 'value（值）' },
    { value: 'text', label: 'text（文本）' },
    { value: 'remark', label: 'remark（备注）' },
];

export default {
    menu: 'main',
    icon: 'icon-link',
    label: label,
    name: name,
    input: true,
    mask: true,
    event: ['change', 'open-detail'],
    rule() {
        return {
            type: name,
            field: uniqueId(),
            title: label,
            info: '关联记录卡片：配置「数据源 + 记录 ID 字段」后实时展示关联记录最新数据（多字段摘要+详情弹窗）；业务表单/审批表单发布后自动生成对应数据源，也可关联系统用户/流程实例等非表单数据；未配置时只读展示当前值',
            $required: false,
            props: {
                title: '',
                description: '',
                displayField: '',
                sourceFormKey: '',
                dataSourceId: '',
                globalDataSourceId: '',
                recordIdField: '',
                displayFields: [],
                clickable: true,
            },
        };
    },
    // 第一参 rule = 设计器当前选中组件的活跃画布 rule（FcDesigner.getPropsRule 传入，
    // 响应式对象）；透传给 RelationFieldSelect 以 watch 数据源/源表单键动态加载字段列表，
    // 并供 ds 选择器执行旧版 sourceFormKey → FORM 数据源自动迁移
    props(rule, { t }) {
        return localeProps(t, name + '.props', [
            {
                type: 'input',
                field: 'title',
                title: '卡片标题',
            },
            {
                type: 'input',
                field: 'description',
                title: '说明文字',
            },
            {
                type: 'RelationFieldSelect',
                field: 'globalDataSourceId',
                title: '数据源',
                info: '关联目标数据源（唯一来源入口）：业务表单/审批表单发布后自动生成对应数据源，也可关联系统用户/部门/岗位/流程实例/外部 API/SQL；打开面板时旧版「源表单」配置自动迁移为对应数据源；值不冗余存储，源数据变更后卡片始终展示最新值',
                props: {
                    mode: 'ds',
                    placeholder: '选择已启用的数据源',
                    allowCreate: false,
                    clearable: true,
                    canvasRule: rule,
                    migrateFromField: 'sourceFormKey',
                },
            },
            {
                type: 'input',
                field: 'recordIdField',
                title: '记录 ID 字段',
                info: '保存关联记录 id 的字段名：数据引用字段填其字段名（值即 id 数组）；查找带回填「记录ID字段」配置的同名字段。留空则用本组件自身值',
                props: { placeholder: '如 customerId', clearable: true },
            },
            {
                type: 'RelationFieldSelect',
                field: 'displayFields',
                title: '摘要展示字段',
                info: '卡片正文实时展示的字段（配置来源后，下拉自动加载其字段列表，多选可手填）；留空自动取前几个非空字段',
                options: SUMMARY_FIELD_FALLBACK,
                props: {
                    mode: 'field',
                    multiple: true,
                    canvasRule: rule,
                    formKeyField: 'sourceFormKey',
                    dsKeyField: 'globalDataSourceId',
                    fallbackOptions: SUMMARY_FIELD_FALLBACK,
                    allowCreate: true,
                    clearable: true,
                    placeholder: '选择或输入源字段名',
                },
            },
            {
                type: 'switch',
                field: 'clickable',
                title: '可点击查看详情',
            },
        ]);
    },
};
