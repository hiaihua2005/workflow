package com.workflow.engine.logic.executor;

import com.workflow.engine.logic.BackendBeanRegistry;
import com.workflow.engine.logic.annotation.BackendLogicBean;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 脚本 Bean 门面沙箱验证：{@code beans.<bean>.<method>(args)} 三种写法
 * （propertyMissing+methodMissing 自然形态 / invoke 显式形态）在 SecureAST 沙箱内可用，
 * 白名单外方法被拒，沙箱既有防线（System.exit 等）不受门面影响。
 */
class ScriptBeanFacadeSandboxTest {

    @BackendLogicBean
    public static class GreetService {
        @SuppressWarnings("unused")
        public String greet(String name) {
            return "hi " + name;
        }

        @SuppressWarnings("unused")
        public int add(int a, int b) {
            return a + b;
        }
    }

    private GroovyScriptLogic newLogic() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansWithAnnotation(BackendLogicBean.class))
                .thenReturn(Map.of("greetService", new GreetService()));
        when(ctx.getBean("greetService")).thenReturn(new GreetService());
        BackendBeanRegistry registry = new BackendBeanRegistry(ctx);
        return new GroovyScriptLogic(5_000L, new ScriptBeanFacade(registry));
    }

    private final GroovyScriptLogic logic = newLogic();

    private Object run(String script) {
        DelegateExecution execution = null;
        return logic.execute(script, execution, Map.of("suffix", "!"));
    }

    @Test
    void beansPropertyThenInvoke_explicitForm() {
        Object r = run("beans.greetService.invoke('greet', 'Sam') + suffix");
        assertEquals("hi Sam!", r);
    }

    @Test
    void beansPropertyThenNaturalMethodCall_methodMissing() {
        // 首选自然形态：beans.<bean>.<method>(args)（propertyMissing + methodMissing 动态分发）
        Object r = run("beans.greetService.greet('Sam')");
        assertEquals("hi Sam", r);
    }

    @Test
    void beansChainWithVars_mixedUsage() {
        Object r = run("def h = beans.greetService\nh.add(2, 3) + h.invoke('add', 1, 1)");
        assertEquals(7, r);
    }

    @Test
    void unregisteredMethod_rejected() {
        Exception ex = assertThrows(RuntimeException.class, () ->
                run("beans.greetService.notRegistered(1)"));
        assertTrue(String.valueOf(ex.getMessage()).contains("not registered")
                        || String.valueOf(ex.getCause() != null ? ex.getCause().getMessage() : "")
                        .contains("not registered"),
                "白名单拒绝应透出 not registered 语义，实际: " + ex.getMessage());
    }

    @Test
    void sandboxStillRejectsSystemExit() {
        assertThrows(Exception.class, () -> run("System.exit(0)"));
    }

    @Test
    void noFacade_noBeansBinding() {
        GroovyScriptLogic bare = new GroovyScriptLogic(5_000L);
        Exception ex = assertThrows(Exception.class, () ->
                bare.execute("beans.greetService.greet('x')", null, Map.of()));
        // 无门面时 beans 未定义（MissingPropertyException 包装为运行时失败）
        assertTrue(ex.getMessage() != null || ex.getCause() != null);
    }
}
