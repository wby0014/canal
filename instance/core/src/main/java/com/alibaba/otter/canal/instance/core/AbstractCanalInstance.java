package com.alibaba.otter.canal.instance.core;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.common.AbstractCanalLifeCycle;
import com.alibaba.otter.canal.common.alarm.CanalAlarmHandler;
import com.alibaba.otter.canal.filter.aviater.AviaterRegexFilter;
import com.alibaba.otter.canal.meta.CanalMetaManager;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.parse.ha.CanalHAController;
import com.alibaba.otter.canal.parse.ha.HeartBeatHAController;
import com.alibaba.otter.canal.parse.inbound.AbstractEventParser;
import com.alibaba.otter.canal.parse.inbound.group.GroupEventParser;
import com.alibaba.otter.canal.parse.inbound.mysql.MysqlEventParser;
import com.alibaba.otter.canal.parse.index.CanalLogPositionManager;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.canal.store.CanalEventStore;
import com.alibaba.otter.canal.store.model.Event;

/**
 * 总结：
 * AbstractCanalInstance源码到目前我们已经分析完成，无非就是在start和stop时，
 * 按照一定的顺序启动或停止event store、event sink、event parser、meta manager这几个组件，
 * 期间对于event parser的启动和停止做了特殊处理，并没有提供订阅binlog的相关方法。
 * 那么如何来订阅binglog数据呢？答案是直接操作器内部组件
 *
 * Created with Intellig IDEA. Author: yinxiu Date: 2016-01-07 Time: 22:26
 */
public class AbstractCanalInstance extends AbstractCanalLifeCycle implements CanalInstance {

    private static final Logger                      logger = LoggerFactory.getLogger(AbstractCanalInstance.class);

    protected Long                                   canalId;                                                      // 和manager交互唯一标示
    protected String                                 destination;                                                  // 队列名字
    protected CanalEventStore<Event>                 eventStore;                                                   // 有序队列

    protected CanalEventParser                       eventParser;                                                  // 解析对应的数据信息
    protected CanalEventSink<List<CanalEntry.Entry>> eventSink;                                                    // 链接parse和store的桥接器
    protected CanalMetaManager                       metaManager;                                                  // 消费信息管理器
    protected CanalAlarmHandler                      alarmHandler;                                                 // alarm报警机制

    @Override
    public boolean subscribeChange(ClientIdentity identity) {
        // client通过canalConnector订阅的时候，会更新一下filter
        // 主要是更新一下eventParser中的filter
        // 关于filter，进行一下补充说明，filter规定了需要订阅哪些库，哪些表。在服务端和客户端都可以设置，客户端的配置会覆盖服务端的配置
        if (StringUtils.isNotEmpty(identity.getFilter())) {
            logger.info("subscribe filter change to " + identity.getFilter());
            AviaterRegexFilter aviaterFilter = new AviaterRegexFilter(identity.getFilter());

            boolean isGroup = (eventParser instanceof GroupEventParser);
            if (isGroup) {
                // 处理group的模式
                List<CanalEventParser> eventParsers = ((GroupEventParser) eventParser).getEventParsers();
                for (CanalEventParser singleEventParser : eventParsers) {// 需要遍历启动
                    ((AbstractEventParser) singleEventParser).setEventFilter(aviaterFilter);
                }
            } else {
                ((AbstractEventParser) eventParser).setEventFilter(aviaterFilter);
            }

        }


        // filter的处理规则
        // a. parser处理数据过滤处理
        // b. sink处理数据的路由&分发,一份parse数据经过sink后可以分发为多份，每份的数据可以根据自己的过滤规则不同而有不同的数据
        // 后续内存版的一对多分发，可以考虑
        return true;
    }

    @Override
    public void start() {
        super.start();
        if (!metaManager.isStart()) {
            metaManager.start();
        }

        if (!alarmHandler.isStart()) {
            alarmHandler.start();
        }

        if (!eventStore.isStart()) {
            eventStore.start();
        }

        if (!eventSink.isStart()) {
            eventSink.start();
        }

        if (!eventParser.isStart()) {
            // eventParser在启动之前，需要先启动CanalLogPositionManager和CanalHAController
            beforeStartEventParser(eventParser);
            eventParser.start();
            afterStartEventParser(eventParser);
        }
        logger.info("start successful....");
    }

    @Override
    public void stop() {
        super.stop();
        logger.info("stop CannalInstance for {}-{} ", new Object[] { canalId, destination });

        if (eventParser.isStart()) {
            beforeStopEventParser(eventParser);
            eventParser.stop();
            afterStopEventParser(eventParser);
        }

        if (eventSink.isStart()) {
            eventSink.stop();
        }

        if (eventStore.isStart()) {
            eventStore.stop();
        }

        if (metaManager.isStart()) {
            metaManager.stop();
        }

        if (alarmHandler.isStart()) {
            alarmHandler.stop();
        }

        logger.info("stop successful....");
    }

    /**
     * beforeStartEventParser方法的作用是eventParser前做的一些特殊处理。
     * 首先会判断eventParser的类型是否是GroupEventParser，在前面我已经介绍过，这是为了处理分库分表的情况。
     * 如果是，循环其包含的所有CanalEventParser，依次调用startEventParserInternal方法；
     * 否则直接调用com.alibaba.otter.canal.instance.core.AbstractCanalInstance#beforeStartEventParser
     * @param eventParser
     */
    protected void beforeStartEventParser(CanalEventParser eventParser) {

        boolean isGroup = (eventParser instanceof GroupEventParser);
        if (isGroup) {
            // 处理group的模式
            List<CanalEventParser> eventParsers = ((GroupEventParser) eventParser).getEventParsers();
            for (CanalEventParser singleEventParser : eventParsers) {// 需要遍历启动
                startEventParserInternal(singleEventParser, true);
            }
        } else {
            startEventParserInternal(eventParser, false);
        }
    }

    // around event parser, default impl
    protected void afterStartEventParser(CanalEventParser eventParser) {
        // 读取一下历史订阅的filter信息
        // 通过metaManager读取一下历史订阅过这个CanalInstance的客户端信息，然后更新一下filter。
        List<ClientIdentity> clientIdentitys = metaManager.listAllSubscribeInfo(destination);
        for (ClientIdentity clientIdentity : clientIdentitys) {
            subscribeChange(clientIdentity);
        }
    }

    // around event parser
    protected void beforeStopEventParser(CanalEventParser eventParser) {
        // noop
    }

    protected void afterStopEventParser(CanalEventParser eventParser) {

        boolean isGroup = (eventParser instanceof GroupEventParser);
        if (isGroup) {
            // 处理group的模式
            List<CanalEventParser> eventParsers = ((GroupEventParser) eventParser).getEventParsers();
            for (CanalEventParser singleEventParser : eventParsers) {// 需要遍历启动
                stopEventParserInternal(singleEventParser);
            }
        } else {
            stopEventParserInternal(eventParser);
        }
    }

    /**
     * 初始化单个eventParser，不需要考虑group
     */
    protected void startEventParserInternal(CanalEventParser eventParser, boolean isGroup) {
        // 关于CanalLogPositionManager，做一点补充说明。
        // mysql在主从同步过程中，要求slave自己维护binlog的消费进度信息。canal伪装成slave，因此也要维护这样的信息。
        // 事实上，如果读者自己搭建过mysql主从复制的话，在slave机器的data目录下，都会有一个master.info文件，这个文件的作用就是存储主库的消费binlog解析进度信息
        if (eventParser instanceof AbstractEventParser) {
            AbstractEventParser abstractEventParser = (AbstractEventParser) eventParser;
            // 首先启动log position管理器
            CanalLogPositionManager logPositionManager = abstractEventParser.getLogPositionManager();
            if (!logPositionManager.isStart()) {
                logPositionManager.start();
            }
        }

        if (eventParser instanceof MysqlEventParser) {
            MysqlEventParser mysqlEventParser = (MysqlEventParser) eventParser;
            CanalHAController haController = mysqlEventParser.getHaController();

            if (haController instanceof HeartBeatHAController) {
                ((HeartBeatHAController) haController).setCanalHASwitchable(mysqlEventParser);
            }

            if (!haController.isStart()) {
                haController.start();
            }

        }
    }

    protected void stopEventParserInternal(CanalEventParser eventParser) {
        if (eventParser instanceof AbstractEventParser) {
            AbstractEventParser abstractEventParser = (AbstractEventParser) eventParser;
            // 首先启动log position管理器
            CanalLogPositionManager logPositionManager = abstractEventParser.getLogPositionManager();
            if (logPositionManager.isStart()) {
                logPositionManager.stop();
            }
        }

        if (eventParser instanceof MysqlEventParser) {
            MysqlEventParser mysqlEventParser = (MysqlEventParser) eventParser;
            CanalHAController haController = mysqlEventParser.getHaController();
            if (haController.isStart()) {
                haController.stop();
            }
        }
    }

    // ==================getter==================================
    @Override
    public String getDestination() {
        return destination;
    }

    @Override
    public CanalEventParser getEventParser() {
        return eventParser;
    }

    @Override
    public CanalEventSink getEventSink() {
        return eventSink;
    }

    @Override
    public CanalEventStore getEventStore() {
        return eventStore;
    }

    @Override
    public CanalMetaManager getMetaManager() {
        return metaManager;
    }

    @Override
    public CanalAlarmHandler getAlarmHandler() {
        return alarmHandler;
    }
}
