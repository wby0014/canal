package com.alibaba.otter.canal.instance.core;

import com.alibaba.otter.canal.common.CanalLifeCycle;
import com.alibaba.otter.canal.common.alarm.CanalAlarmHandler;
import com.alibaba.otter.canal.meta.CanalMetaManager;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.canal.store.CanalEventStore;

/**
 * 代表单个canal实例，比如一个destination会独立一个实例
 *
 *  每个实例处理的流程大致是： mysql_master -> canalInstance(伪装slave角色） -> eventParser -> eventSink -> eventStore -> metaManager -> 客户端拉取binlog
 * @author jianghang 2012-7-12 下午12:04:58
 * @version 1.0.0
 */
public interface CanalInstance extends CanalLifeCycle {

    String getDestination();

    // 数据源接入，模拟slave协议和master进行交互，协议解析，与mysql-master建立连接，发送dump请求，创建DirectLogFetcher日志获取器循环读取binlog日志数据字节流
    CanalEventParser getEventParser();

    // parser和store链接器，进行数据过滤，加工，分发的工作
    CanalEventSink getEventSink();

    // 数据存储
    CanalEventStore getEventStore();

    // 增量订阅/消费binlog元数据位置存储
    CanalMetaManager getMetaManager();

    CanalAlarmHandler getAlarmHandler();

    /**
     * 客户端发生订阅/取消订阅行为
     */
    boolean subscribeChange(ClientIdentity identity);
}
