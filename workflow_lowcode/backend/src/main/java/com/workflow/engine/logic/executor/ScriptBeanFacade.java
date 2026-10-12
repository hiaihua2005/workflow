package com.workflow.engine.logic.executor;

import com.workflow.engine.logic.BackendBeanRegistry;

/**
 * Groovy 脚本 Bean 门面：把 {@link BackendBeanRegistry} 白名单 Bean 以
 * {@code beans.<beanName>.<methodName>(args…)} 形态暴露给脚本。
 *
 * <p>设计约束（与 BEAN 节点同一安全边界）：
 * <ul>
 *   <li>仅暴露注册表中标注 {@code @BackendLogicBean} 的 Bean；方法调用一律经
 *       {@link BackendBeanRegistry#invoke} 走白名单校验 + 参数个数校验，
 *       脚本拿不到 Bean 实例本身（无反射逃逸面）；</li>
 *   <li>门面/句柄自身无危险方法、不持有可变状态，SecureAST 黑名单不受影响；</li>
 *   <li>语法（Groovy MOP 动态分发，编译期均为普通属性/方法调用表达式）：
 *       {@code beans.postService.findPosts(1, 2)}；亦可用显式形态
 *       {@code beans.postService.invoke('findPosts', 1, 2)}（语义等价）。</li>
 * </ul>
 *
 * <p>绑定名 {@code beans} 为流程变量保留名兜底：同名流程变量在脚本上下文中优先
 * （{@link GroovyScriptLogic} 先放门面、后放流程变量）。
 */
public final class ScriptBeanFacade {

    private final BackendBeanRegistry registry;

    public ScriptBeanFacade(BackendBeanRegistry registry) {
        this.registry = registry;
    }

    /** Groovy 属性分发：{@code beans.xxx} → 该 Bean 的调用句柄。 */
    public Object propertyMissing(String name) {
        return of(name);
    }

    /** 显式取句柄（Java/Groovy 均可）：{@code beans.of('xxx')}。 */
    public Handle of(String beanName) {
        return new Handle(beanName == null ? "" : beanName);
    }

    /** 单个 Bean 的调用句柄：方法调用经注册表白名单校验后反射执行。 */
    public final class Handle {

        private final String beanName;

        private Handle(String beanName) {
            this.beanName = beanName;
        }

        public String getBeanName() {
            return beanName;
        }

        /** 显式调用白名单方法：{@code h.invoke('methodName', a, b)}。 */
        public Object invoke(String methodName, Object... args) {
            return registry.invoke(beanName, methodName, args == null ? new Object[0] : args);
        }

        /** Groovy 方法分发：{@code beans.xxx.methodName(a, b)} → 白名单校验后反射调用。 */
        public Object methodMissing(String methodName, Object args) {
            Object[] arr = args instanceof Object[] oa ? oa : new Object[] { args };
            return registry.invoke(beanName, methodName, arr);
        }

        @Override
        public String toString() {
            return "ScriptBeanHandle(" + beanName + ")";
        }
    }
}
