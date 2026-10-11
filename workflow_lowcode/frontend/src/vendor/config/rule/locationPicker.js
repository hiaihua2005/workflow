/**
 * 定位 LocationPicker v3（地图选点+命名坐标双持久化）——表单设计器「基础组件」分组 vendor rule。
 *
 * 与 signaturePad.js 同构（menu:'main' + input + mask）；点击弹出 Leaflet 地图
 * （高德 XYZ 瓦片免 key，GCJ-02），点选位置 → 可命名 → 确定写回；组件默认显示位置名称。
 * 值形态（详见 src/utils/locationValue.ts）：命名 → JSON 复合文本（name+lat+lng 双持久化，
 * v3 修复 v2 命名丢坐标缺陷）| 'lat,lng'（未命名兜底，兼容 v1 坐标值）| ''（VARCHAR(255) 直存）。
 * - props 面板：placeholder / disabled / clearable；地图交互仅在预览/运行态
 *   （画布 mask 遮罩，避免设计态误触弹窗）
 * - 各 prop 的 title 内联中文，不依赖 locale 新增词条（localeProps 找不到
 *   com.LocationPicker.props.* 时回退 title；主会话后续如补 locale 词条会自动覆盖）
 *
 * 【接线点（由主会话统一执行，本文件不自行注册）】
 * 1. src/vendor/config/index.js：import locationPicker from './rule/locationPicker'，并加入 ruleList
 * 2. src/main.ts：import LocationPicker from '@/components/business/LocationPicker.vue' +
 *    FcDesigner.component('LocationPicker', LocationPicker)（设计器画布 + 运行时渲染双实例可见）
 * 3. rule.type 'LocationPicker' 与组件注册名一致，画布/运行态按该名解析组件
 */
import uniqueId from '@form-create/utils/lib/unique';
import { localeProps } from '../../utils';

const label = '定位';
const name = 'LocationPicker';

export default {
    menu: 'main',
    icon: 'icon-location',
    label: label,
    name: name,
    input: true,
    mask: true,
    event: ['change'],
    rule() {
        return {
            type: name,
            field: uniqueId(),
            title: label,
            info: '点击弹出地图选点，可对位置命名；名称与坐标同时保存，重开可回放标记',
            $required: false,
            props: {
                placeholder: '点击选择位置',
                disabled: false,
                clearable: true,
            },
        };
    },
    props(_, { t }) {
        return localeProps(t, name + '.props', [
            {
                type: 'input',
                field: 'placeholder',
                title: '占位文案',
            },
            {
                type: 'switch',
                field: 'disabled',
                title: '禁用',
            },
            {
                type: 'switch',
                field: 'clearable',
                title: '显示清空按钮',
            },
        ]);
    },
};
