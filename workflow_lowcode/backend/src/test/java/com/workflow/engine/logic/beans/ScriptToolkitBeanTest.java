package com.workflow.engine.logic.beans;

import com.workflow.engine.logic.BackendBeanRegistry;
import com.workflow.engine.logic.annotation.BackendLogicBean;
import com.workflow.engine.logic.executor.GroovyScriptLogic;
import com.workflow.engine.logic.executor.ScriptBeanFacade;
import com.workflow.system.domain.vo.DictDataVO;
import com.workflow.system.service.DictDataService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ScriptToolkitBean 白名单注册 + Groovy 沙箱经 beans 门面端到端调用验证。
 */
class ScriptToolkitBeanTest {

    @BackendLogicBean
    public static class Toolkit {
        private final DictDataService dict;

        Toolkit(DictDataService dict) {
            this.dict = dict;
        }

        public String dictLabel(String dictCode, String value) {
            if (dictCode == null || dictCode.isBlank() || value == null || value.isBlank()) {
                return value;
            }
            return dict.list(dictCode).stream()
                    .filter(d -> value.equals(d.value()))
                    .map(DictDataVO::label)
                    .findFirst()
                    .orElse(value);
        }

        public Map<String, String> dictMap(String dictCode) {
            return Map.of();
        }

        public String now() {
            return "2026-10-12 10:00:00";
        }

        public String uuid() {
            return "abc123";
        }
    }

    private ApplicationContext toolkitContext() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansWithAnnotation(BackendLogicBean.class))
                .thenReturn(Map.of("scriptToolkit", new Toolkit(mock(DictDataService.class))));
        when(ctx.getBean("scriptToolkit")).thenReturn(new Toolkit(mock(DictDataService.class)));
        return ctx;
    }

    private GroovyScriptLogic newLogic() {
        DictDataService dict = mock(DictDataService.class);
        when(dict.list(anyString())).thenReturn(List.of(
                new DictDataVO(1L, "leave_type", "年假", "ANNUAL", 1, 1, null),
                new DictDataVO(2L, "leave_type", "事假", "PERSONAL", 2, 1, null)
        ));
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansWithAnnotation(BackendLogicBean.class))
                .thenReturn(Map.of("scriptToolkit", new Toolkit(dict)));
        when(ctx.getBean("scriptToolkit")).thenReturn(new Toolkit(dict));
        BackendBeanRegistry registry = new BackendBeanRegistry(ctx);
        return new GroovyScriptLogic(5_000L, new ScriptBeanFacade(registry));
    }

    private final GroovyScriptLogic logic = newLogic();

    @Test
    void registryListsToolkitMethods() {
        BackendBeanRegistry registry = new BackendBeanRegistry(toolkitContext());
        assertTrue(registry.listMethods().stream().anyMatch(m ->
                m.beanName().equals("scriptToolkit") && m.methodName().equals("dictLabel")));
    }

    @Test
    void sandboxBeansNaturalCall_dictLabel() {
        Object r = logic.execute("beans.scriptToolkit.dictLabel('leave_type', 'ANNUAL')", null, Map.of());
        assertEquals("年假", r);
    }

    @Test
    void sandboxBeansNaturalCall_missReturnsValue() {
        Object r = logic.execute("beans.scriptToolkit.dictLabel('leave_type', 'UNKNOWN')", null, Map.of());
        assertEquals("UNKNOWN", r);
    }

    @Test
    void sandboxBeansNowAndUuid() {
        Object r = logic.execute("[beans.scriptToolkit.now(), beans.scriptToolkit.uuid()]", null, Map.of());
        assertNotNull(r);
        List<?> list = (List<?>) r;
        assertEquals("2026-10-12 10:00:00", list.get(0));
        assertEquals("abc123", list.get(1));
    }

    @Test
    void sandboxBeansExplicitInvokeForm() {
        Object r = logic.execute("beans.scriptToolkit.invoke('now')", null, Map.of());
        assertEquals("2026-10-12 10:00:00", r);
    }

    @Test
    void sandboxRejectsUnregisteredMethod() {
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                logic.execute("beans.scriptToolkit.notThere()", null, Map.of()));
        String msg = String.valueOf(ex.getMessage()) + ex.getCause();
        assertTrue(msg.contains("not registered"), "白名单拒绝应透出: " + msg);
    }
}
