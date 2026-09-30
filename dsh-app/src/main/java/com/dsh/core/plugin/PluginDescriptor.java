package com.dsh.core.plugin;

/**
 * 插件自述信息, 对应 dsh Cordis ctx 里插件向宿主登记的元数据。
 *
 * <p>这是插件装配机制里宿主侧唯一认识的东西: 宿主不 import 任何具体插件,
 * 只把 classpath 上出现的 PluginDescriptor bean 收集起来展示 (GET /api/plugins)。
 * 插件模块声明一个这样的 bean, 就等于在共享上下文里"报到" -- Cordis ctx 的最小复刻。
 */
public record PluginDescriptor(String name, String description) {
}
