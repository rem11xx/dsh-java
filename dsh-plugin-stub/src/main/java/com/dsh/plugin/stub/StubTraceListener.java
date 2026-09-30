package com.dsh.plugin.stub;

import com.dsh.core.session.event.TurnEndEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * 插件侧事件监听器: 监听轮次结束, 打一行带插件前缀的日志。
 *
 * <p>和宿主的 CLI trace / SSE 广播一样, 它只是 loop 广播事件的又一个消费者 --
 * "事件即扩展点"的插件版证明: 新功能靠加监听者落地, AgentLoop 不用动。
 */
public class StubTraceListener {

    private static final Logger log = LoggerFactory.getLogger(StubTraceListener.class);

    @EventListener
    public void onTurnEnd(TurnEndEvent event) {
        log.info("[plugin-stub] turn {} 结束 ({})", event.data().turn(), event.data().reason());
    }
}
