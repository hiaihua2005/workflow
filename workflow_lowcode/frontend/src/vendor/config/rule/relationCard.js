/**
 * 关联记录卡片 RelationCard（Task 3-g）——表单设计器「基础组件」分组 vendor rule。
 *
 * 与 signaturePad.js 同构（menu:'main' + input + mask）；差异点：
 * - 只读展示组件：v2 实时模式（sourceFormKey + recordIdField）下监听记录 id 实时拉取
 *   源表单最新整行（bizDataApi.detail），多字段摘要 + 详情弹窗 + 刷新；不配置则保持
 *   v1 被动模式（值由关联记录选择/联动规则写入，仅做摘要展示）
 * - props 面板：title（卡内标题）/ description（说明文字）/ displayField（被动模式对象取显示字段）/
 *   sourceFormKey（源表单）/ recordIdField（记录 id 所在字段）/ displayFields（摘要字段多选）/
 *   clickable（是否可点击查看详情）；三项均使用 RelationFieldSelect 动态下拉：
 *   源表单=已发布业务表单列表，两个字段下拉=源表单字段列表（columnConfig/schema，
 *   未配置或加载失败回退静态常用字段，始终可手填）
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
const DISPLAY_FIELD_FALLBACK = [
    { value: 'name', label: 'name（名称）' },
    { value: 'title', label: 'title（标题）' },
    { value: 'label', label: 'label（标签）' },
    { value: 'id', label: 'id（记录 ID）' },
    { value: 'code', label: 'code（编码）' },
    { value: 'status', label: 'status（状态）' },
    { value: 'value', label: 'value（值）' },
    { value: 'text', label: 'text（文本）' },
    { value: 'remark', label: 'remark（备注）' },
];

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
            info: '关联记录卡片：配置「源表单 Key + 记录 ID 字段」后实时展示关联记录最新数据（多字段摘要+详情弹窗）；未配置时只读展示当前值',
            $required: false,
            props: {
                title: '',
                description: '',
                displayField: '',
                sourceFormKey: '',
                recordIdField: '',
                displayFields: [],
                clickable: true,
            },
        };
    },
    // 第一参 rule = 设计器当前选中组件的活跃画布 rule（FcDesigner.getPropsRule 传入，
    // 响应式对象）；透传给 RelationFieldSelect 以 watch sourceFormKey 动态加载字段列表
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
                field: 'sourceFormKey',
                title: '源表单',
                info: '关联目标业务表单（下拉列出已发布业务表单，可手填 key）。配置后卡片实时拉取该记录最新数据（非快照）；留空则仅展示当前表单值',
                props: {
                    mode: 'form',
                    placeholder: '选择已发布的业务表单',
                    allowCreate: true,
                    clearable: true,
                },
            },
            {
                type: 'RelationFieldSelect',
                field: 'displayField',
                title: '显示字段',
                info: '值为对象时按该字段取显示文本（被动模式取值）；配置源表单后，下拉自动加载该表单字段列表（中文 label + 字段名），也可手填',
                options: DISPLAY_FIELD_FALLBACK,
                props: {
                    mode: 'field',
                    multiple: false,
                    canvasRule: rule,
                    formKeyField: 'sourceFormKey',
                    fallbackOptions: DISPLAY_FIELD_FALLBACK,
                    allowCreate: true,
                    clearable: true,
                    placeholder: '选择或输入字段名',
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
                info: '卡片正文实时展示的字段（配置源表单后，下拉自动加载该表单字段列表，多选可手填）；留空自动取前几个非空字段',
                options: SUMMARY_FIELD_FALLBACK,
                props: {
                    mode: 'field',
                    multiple: true,
                    canvasRule: rule,
                    formKeyField: 'sourceFormKey',
                    fallbackOptions: SUMMARY_FIELD_FALLBACK,
                    allowCreate: true,
                    clearable: true,
                    placeholder: '选择或输入源表单字段名',
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
