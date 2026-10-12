package com.workflow.engine.logic.beans;

import com.workflow.engine.logic.annotation.BackendLogicBean;
import com.workflow.system.domain.vo.DictDataVO;
import com.workflow.system.service.DictDataService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 脚本/流程通用只读工具 Bean（白名单注册）。
 *
 * <p>标注 {@link BackendLogicBean} 后，方法进入后端逻辑白名单：
 * <ul>
 *   <li>BEAN 节点下拉可选；</li>
 *   <li>Groovy 脚本可 {@code beans.scriptToolkit.<method>(args)} 调用
 *       （ScriptBeanFacade 门面，同一白名单语义）。</li>
 * </ul>
 *
 * <p>定位：脚本沙箱内不便于完成的常用只读操作——字典翻译、时间格式化、UUID 生成。
 * 全部只读无副作用，写操作不在本 Bean 暴露。
 */
@BackendLogicBean
@Component("scriptToolkit")
public class ScriptToolkitBean {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DictDataService dictDataService;

    public ScriptToolkitBean(DictDataService dictDataService) {
        this.dictDataService = dictDataService;
    }

    /** 字典翻译：dictCode + value → label（未命中返回原值，脚本侧零分支可用）。 */
    public String dictLabel(String dictCode, String value) {
        if (dictCode == null || dictCode.isBlank() || value == null || value.isBlank()) {
            return value;
        }
        return dictDataService.list(dictCode).stream()
                .filter(d -> value.equals(d.value()))
                .map(DictDataVO::label)
                .findFirst()
                .orElse(value);
    }

    /** 字典键值映射：dictCode → { value: label }（保序，脚本遍历/下拉填充用）。 */
    public Map<String, String> dictMap(String dictCode) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dictCode == null || dictCode.isBlank()) {
            return out;
        }
        for (DictDataVO d : dictDataService.list(dictCode)) {
            out.put(d.value(), d.label());
        }
        return out;
    }

    /** 当前时间（yyyy-MM-dd HH:mm:ss 字符串，脚本内格式化受限场景）。 */
    public String now() {
        return LocalDateTime.now().format(TS);
    }

    /** 生成无连字符 UUID（业务单号/幂等键）。 */
    public String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
