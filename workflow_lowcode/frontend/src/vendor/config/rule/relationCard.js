/**
 * 关联记录卡片 RelationCard（Task 3-g）——表单设计器「基础组件」分组 vendor rule。
 *
 * 与 signaturePad.js 同构（menu:'main' + input + mask）；差异点：
 * - 只读展示组件：无用户输入，值由关联记录选择（LookupPicker 值形态：显示文本字符串 /
 *   整行对象，id 走 idField 独立字段）回显，或由联动规则写入
 * - props 面板：title（卡内标题）/ description（说明文字）/ displayField（对象取显示字段）/
 *   clickable（是否可点击查看详情）
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
            info: '只读展示已选关联记录（值形态对齐查找带回：显示文本或整行对象）',
            $required: false,
            props: {
                title: '',
                description: '',
                displayField: '',
                clickable: true,
            },
        };
    },
    props(_, { t }) {
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
                type: 'select',
                field: 'displayField',
                title: '显示字段',
                info: '值为对象时按该字段取显示文本（深层取值）；下拉可选手填自定义字段名',
                options: [
                    { value: 'name', label: 'name（名称）' },
                    { value: 'title', label: 'title（标题）' },
                    { value: 'label', label: 'label（标签）' },
                    { value: 'id', label: 'id（记录 ID）' },
                    { value: 'code', label: 'code（编码）' },
                    { value: 'status', label: 'status（状态）' },
                    { value: 'value', label: 'value（值）' },
                    { value: 'text', label: 'text（文本）' },
                    { value: 'remark', label: 'remark（备注）' },
                ],
                props: {
                    filterable: true,
                    allowCreate: true,
                    defaultFirstOption: false,
                    clearable: true,
                    placeholder: '选择或输入字段名',
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
