package com.alibaba.otter.canal.store.memory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.position.LogPosition;
import com.alibaba.otter.canal.protocol.position.Position;
import com.alibaba.otter.canal.protocol.position.PositionRange;
import com.alibaba.otter.canal.store.AbstractCanalStoreScavenge;
import com.alibaba.otter.canal.store.CanalEventStore;
import com.alibaba.otter.canal.store.CanalStoreException;
import com.alibaba.otter.canal.store.CanalStoreScavenge;
import com.alibaba.otter.canal.store.helper.CanalEventUtils;
import com.alibaba.otter.canal.store.model.BatchMode;
import com.alibaba.otter.canal.store.model.Event;
import com.alibaba.otter.canal.store.model.Events;

/**
 * 基于内存buffer构建内存memory store
 * 
 * <pre>
 * 变更记录：
 * 1. 新增BatchMode类型，支持按内存大小获取批次数据，内存大小更加可控.
 *   a. put操作，会首先根据bufferSize进行控制，然后再进行bufferSize * bufferMemUnit进行控制. 因存储的内容是以Event，如果纯依赖于memsize进行控制，会导致RingBuffer出现动态伸缩
 * </pre>
 * 
 * @author jianghang 2012-6-20 上午09:46:31
 * @version 1.0.0
 */
public class MemoryEventStoreWithBuffer extends AbstractCanalStoreScavenge implements CanalEventStore<Event>, CanalStoreScavenge {

    private static final long INIT_SQEUENCE = -1;
    private int               bufferSize    = 16 * 1024;
    private int               bufferMemUnit = 1024;                         // memsize的单位，默认为1kb大小
    private int               indexMask;  // 用于对putSequence、getSequence、ackSequence进行取余操作，前面已经介绍过canal通过位操作进行取余，其值为bufferSize-1
    private Event[]           entries;   // 环形队列底层基于的Event[]数组，队列大小就是bufferSize

    // 记录下put/get/ack操作的三个下标
    private AtomicLong        putSequence   = new AtomicLong(INIT_SQEUENCE); // 代表当前put操作最后一次写操作发生的位置
    private AtomicLong        getSequence   = new AtomicLong(INIT_SQEUENCE); // 代表当前get操作读取的最后一条的位置
    private AtomicLong        ackSequence   = new AtomicLong(INIT_SQEUENCE); // 代表当前ack操作的最后一条的位置

    // 记录下put/get/ack操作的三个memsize大小
    private AtomicLong        putMemSize    = new AtomicLong(0);
    private AtomicLong        getMemSize    = new AtomicLong(0);
    private AtomicLong        ackMemSize    = new AtomicLong(0);

    // 阻塞put/get操作控制信号
    private ReentrantLock     lock          = new ReentrantLock();
    private Condition         notFull       = lock.newCondition();
    private Condition         notEmpty      = lock.newCondition();

    private BatchMode         batchMode     = BatchMode.ITEMSIZE;           // 默认为内存大小模式
    private boolean           ddlIsolation  = false;

    public MemoryEventStoreWithBuffer(){

    }

    public MemoryEventStoreWithBuffer(BatchMode batchMode){
        this.batchMode = batchMode;
    }

    public void start() throws CanalStoreException {
        super.start();
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        indexMask = bufferSize - 1;
        entries = new Event[bufferSize];
    }

    public void stop() throws CanalStoreException {
        super.stop();

        cleanAll();
    }

    /**
     * 不带timeout超时参数的put方法，会一直进行阻塞，直到有足够的空间可以放入
     * @param data
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public void put(List<Event> data) throws InterruptedException, CanalStoreException {
        if (data == null || data.isEmpty()) {
            return;
        }

        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            try {
                while (!checkFreeSlotAt(putSequence.get() + data.size())) { // 检查是否有空位
                    notFull.await(); // wait until not full
                }
            } catch (InterruptedException ie) {
                notFull.signal(); // propagate to non-interrupted thread
                throw ie;
            }
            doPut(data);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带timeout参数超时参数的put方法，如果超过指定时间还未put成功，会抛出InterruptedException
     * @param data
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public boolean put(List<Event> data, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        if (data == null || data.isEmpty()) {
            return true;
        }

        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                if (checkFreeSlotAt(putSequence.get() + data.size())) {
                    doPut(data);
                    return true;
                }
                if (nanos <= 0) {
                    return false;
                }

                try {
                    nanos = notFull.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    notFull.signal(); // propagate to non-interrupted thread
                    throw ie;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * tryPut方法每次只是尝试放入数据，立即返回true或者false，不会阻塞
     * @param data
     * @return
     * @throws CanalStoreException
     */
    public boolean tryPut(List<Event> data) throws CanalStoreException {
        if (data == null || data.isEmpty()) {
            return true;
        }

        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (!checkFreeSlotAt(putSequence.get() + data.size())) {
                return false;
            } else {
                doPut(data);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    public void put(Event data) throws InterruptedException, CanalStoreException {
        put(Arrays.asList(data));
    }

    public boolean put(Event data, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        return put(Arrays.asList(data), timeout, unit);
    }

    public boolean tryPut(Event data) throws CanalStoreException {
        return tryPut(Arrays.asList(data));
    }

    /**
     * 执行具体的put操作
     * 1、将新插入的event数据赋值到Event[]数组的正确位置上，就算完成了插入
     *
     * 2、当新插入的event记录数累加到putSequence上
     *
     * 3、累加新插入的event的大小到putMemSize上
     *
     * 4、调用notEmpty.signal()方法，通知队列中有数据了，如果之前有client获取数据处于阻塞状态，将会被唤醒
     */
    private void doPut(List<Event> data) {
        long current = putSequence.get();
        long end = current + data.size();

        // 先写数据，再更新对应的cursor,并发度高的情况，putSequence会被get请求可见，拿出了ringbuffer中的老的Entry值
        for (long next = current + 1; next <= end; next++) {
            entries[getIndex(next)] = data.get((int) (next - current - 1));
        }

        putSequence.set(end);

        // 记录一下gets memsize信息，方便快速检索
        if (batchMode.isMemSize()) {
            long size = 0;
            for (Event event : data) {
                // 计算每个event占用的内存大小
                size += calculateSize(event);
            }

            putMemSize.getAndAdd(size);
        }

        // tell other threads that store is not empty
        notEmpty.signal();
    }

    /**
     * 获取binlog数据
     *
     * Put操作是canal parser模块解析binlog事件，并经过sink模块过滤后，放入到store模块中，也就是说Put操作实际上是canal内部调用。
     * Get操作(以及ack、rollback)则不同，其是由client发起的网络请求，server端通过对请求参数进行解析，最终调用CanalEventStore模块中定义的对应方法
     *
     * @param start
     * @param batchSize
     * @return
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public Events<Event> get(Position start, int batchSize) throws InterruptedException, CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            try {
                while (!checkUnGetSlotAt((LogPosition) start, batchSize))
                    notEmpty.await();
            } catch (InterruptedException ie) {
                notEmpty.signal(); // propagate to non-interrupted thread
                throw ie;
            }

            return doGet(start, batchSize);
        } finally {
            lock.unlock();
        }
    }

    public Events<Event> get(Position start, int batchSize, long timeout, TimeUnit unit) throws InterruptedException,
                                                                                        CanalStoreException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                if (checkUnGetSlotAt((LogPosition) start, batchSize)) {
                    return doGet(start, batchSize);
                }

                if (nanos <= 0) {
                    // 如果时间到了，有多少取多少
                    return doGet(start, batchSize);
                }

                try {
                    nanos = notEmpty.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    notEmpty.signal(); // propagate to non-interrupted thread
                    throw ie;
                }

            }
        } finally {
            lock.unlock();
        }
    }

    public Events<Event> tryGet(Position start, int batchSize) throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return doGet(start, batchSize);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 1、确定从哪个位置开始获取数据
     *
     * 2、根据batchMode是MEMSIZE还是ITEMSIZE，通过不同的方式来获取数据
     *
     * 3、设置PositionRange，表示获取到的event列表开始和结束位置
     *
     * 4、设置ack点
     *
     * 5、累加getSequence，getMemSize值
     *
     * @param start
     * @param batchSize
     * @return
     * @throws CanalStoreException
     */
    private Events<Event> doGet(Position start, int batchSize) throws CanalStoreException {
        LogPosition startPosition = (LogPosition) start;

        long current = getSequence.get();
        long maxAbleSequence = putSequence.get();
        long next = current;
        long end = current;
        // 如果startPosition为null，说明是第一次，默认+1处理
        if (startPosition == null || !startPosition.getPostion().isIncluded()) { // 第一次订阅之后，需要包含一下start位置，防止丢失第一条记录
            next = next + 1;
        }

        if (current >= maxAbleSequence) {
            return new Events<Event>();
        }

        Events<Event> result = new Events<Event>();
        List<Event> entrys = result.getEvents();
        long memsize = 0;
        if (batchMode.isItemSize()) {
            end = (next + batchSize - 1) < maxAbleSequence ? (next + batchSize - 1) : maxAbleSequence;
            // 提取数据并返回
            for (; next <= end; next++) {
                Event event = entries[getIndex(next)];
                if (ddlIsolation && isDdl(event.getEntry().getHeader().getEventType())) {
                    // 如果是ddl隔离，直接返回
                    if (entrys.size() == 0) {
                        entrys.add(event);// 如果没有DML事件，加入当前的DDL事件
                        end = next; // 更新end为当前
                    } else {
                        // 如果之前已经有DML事件，直接返回了，因为不包含当前next这记录，需要回退一个位置
                        end = next - 1; // next-1一定大于current，不需要判断
                    }
                    break;
                } else {
                    entrys.add(event);
                }
            }
        } else {
            long maxMemSize = batchSize * bufferMemUnit;
            for (; memsize <= maxMemSize && next <= maxAbleSequence; next++) {
                // 永远保证可以取出第一条的记录，避免死锁
                Event event = entries[getIndex(next)];
                if (ddlIsolation && isDdl(event.getEntry().getHeader().getEventType())) {
                    // 如果是ddl隔离，直接返回
                    if (entrys.size() == 0) {
                        entrys.add(event);// 如果没有DML事件，加入当前的DDL事件
                        end = next; // 更新end为当前
                    } else {
                        // 如果之前已经有DML事件，直接返回了，因为不包含当前next这记录，需要回退一个位置
                        end = next - 1; // next-1一定大于current，不需要判断
                    }
                    break;
                } else {
                    entrys.add(event);
                    memsize += calculateSize(event);
                    end = next;// 记录end位点
                }
            }

        }

        PositionRange<LogPosition> range = new PositionRange<LogPosition>();
        result.setPositionRange(range);

        range.setStart(CanalEventUtils.createPosition(entrys.get(0)));
        range.setEnd(CanalEventUtils.createPosition(entrys.get(result.getEvents().size() - 1)));
        // 记录一下是否存在可以被ack的点

        for (int i = entrys.size() - 1; i >= 0; i--) {
            Event event = entrys.get(i);
            if (CanalEntry.EntryType.TRANSACTIONBEGIN == event.getEntry().getEntryType()
                || CanalEntry.EntryType.TRANSACTIONEND == event.getEntry().getEntryType()
                || isDdl(event.getEntry().getHeader().getEventType())) {
                // 将事务头/尾设置可被为ack的点
                range.setAck(CanalEventUtils.createPosition(event));
                break;
            }
        }

        if (getSequence.compareAndSet(current, end)) {
            getMemSize.addAndGet(memsize);
            notFull.signal();
            return result;
        } else {
            return new Events<Event>();
        }
    }

    public LogPosition getFirstPosition() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            long firstSeqeuence = ackSequence.get();
            if (firstSeqeuence == INIT_SQEUENCE && firstSeqeuence < putSequence.get()) {
                // 没有ack过数据
                Event event = entries[getIndex(firstSeqeuence + 1)]; // 最后一次ack为-1，需要移动到下一条,included
                                                                     // = false
                return CanalEventUtils.createPosition(event, false);
            } else if (firstSeqeuence > INIT_SQEUENCE && firstSeqeuence < putSequence.get()) {
                // ack未追上put操作
                Event event = entries[getIndex(firstSeqeuence + 1)]; // 最后一次ack的位置数据
                                                                     // + 1
                return CanalEventUtils.createPosition(event, true);
            } else if (firstSeqeuence > INIT_SQEUENCE && firstSeqeuence == putSequence.get()) {
                // 已经追上，store中没有数据
                Event event = entries[getIndex(firstSeqeuence)]; // 最后一次ack的位置数据，和last为同一条，included
                                                                 // = false
                return CanalEventUtils.createPosition(event, false);
            } else {
                // 没有任何数据
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    public LogPosition getLatestPosition() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            long latestSequence = putSequence.get();
            if (latestSequence > INIT_SQEUENCE && latestSequence != ackSequence.get()) {
                Event event = entries[(int) putSequence.get() & indexMask]; // 最后一次写入的数据，最后一条未消费的数据
                return CanalEventUtils.createPosition(event, true);
            } else if (latestSequence > INIT_SQEUENCE && latestSequence == ackSequence.get()) {
                // ack已经追上了put操作
                Event event = entries[(int) putSequence.get() & indexMask]; // 最后一次写入的数据，included
                                                                            // =
                                                                            // false
                return CanalEventUtils.createPosition(event, false);
            } else {
                // 没有任何数据
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    public void ack(Position position) throws CanalStoreException {
        cleanUntil(position);
    }

    public void cleanUntil(Position position) throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            long sequence = ackSequence.get();
            long maxSequence = getSequence.get();

            boolean hasMatch = false;
            long memsize = 0;
            for (long next = sequence + 1; next <= maxSequence; next++) {
                Event event = entries[getIndex(next)];
                memsize += calculateSize(event);
                boolean match = CanalEventUtils.checkPosition(event, (LogPosition) position);
                if (match) {// 找到对应的position，更新ack seq
                    hasMatch = true;

                    if (batchMode.isMemSize()) {
                        ackMemSize.addAndGet(memsize);
                        // 尝试清空buffer中的内存，将ack之前的内存全部释放掉
                        for (long index = sequence + 1; index < next; index++) {
                            entries[getIndex(index)] = null;// 设置为null
                        }
                    }

                    if (ackSequence.compareAndSet(sequence, next)) {// 避免并发ack
                        notFull.signal();
                        return;
                    }
                }
            }

            if (!hasMatch) {// 找不到对应需要ack的position
                throw new CanalStoreException("no match ack position" + position.toString());
            }
        } finally {
            lock.unlock();
        }
    }

    public void rollback() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            getSequence.set(ackSequence.get());
            getMemSize.set(ackMemSize.get());
        } finally {
            lock.unlock();
        }
    }

    public void cleanAll() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            putSequence.set(INIT_SQEUENCE);
            getSequence.set(INIT_SQEUENCE);
            ackSequence.set(INIT_SQEUENCE);

            putMemSize.set(0);
            getMemSize.set(0);
            ackMemSize.set(0);
            entries = null;
            // for (int i = 0; i < entries.length; i++) {
            // entries[i] = null;
            // }
        } finally {
            lock.unlock();
        }
    }

    // =================== helper method =================

    private long getMinimumGetOrAck() {
        long get = getSequence.get();
        long ack = ackSequence.get();
        return ack <= get ? ack : get;
    }

    /**
     * 查询是否有空位
     * "putSequence + need_put_events_size"的结果为添加数据后的putSequence的最终位置值，
     * 要把这个作为预判断条件，其减去ackSequence，如果大于bufferSize，则不能插入数据。需要等待有足够的空间，或者抛出异常
     */
    private boolean checkFreeSlotAt(final long sequence) {
        // 默认的bufferSize设置大小为16384，即有16384个slot，每个slot可以存储一个event，因此canal默认最多缓存16384个event。
        // 从来另一个角度出发，这意味着putSequence最多比ackSequence可以大16384，不能超过这个值。
        // 如果超过了，就意味着尚未没有被消费的数据被覆盖了，相当于丢失了数据。
        // 因此，如果Put操作满足以下条件(putSequence + need_put_events_size)- ackSequence > bufferSize时，是不能新加入数据的
        final long wrapPoint = sequence - bufferSize;
        final long minPoint = getMinimumGetOrAck();
        if (wrapPoint > minPoint) { // 刚好追上一轮
            return false;
        } else {
            // 在bufferSize模式上，再增加memSize控制
            if (batchMode.isMemSize()) {
                final long memsize = putMemSize.get() - ackMemSize.get();
                if (memsize < bufferSize * bufferMemUnit) {
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }
    }

    /**
     * 检查是否存在需要get的数据,并且数量>=batchSize
     */
    private boolean checkUnGetSlotAt(LogPosition startPosition, int batchSize) {
        if (batchMode.isItemSize()) {
            long current = getSequence.get();
            long maxAbleSequence = putSequence.get();
            long next = current;
            if (startPosition == null || !startPosition.getPostion().isIncluded()) { // 第一次订阅之后，需要包含一下start位置，防止丢失第一条记录
                next = next + 1;// 少一条数据
            }

            if (current < maxAbleSequence && next + batchSize - 1 <= maxAbleSequence) {
                return true;
            } else {
                return false;
            }
        } else {
            // 处理内存大小判断
            long currentSize = getMemSize.get();
            long maxAbleSize = putMemSize.get();

            if (maxAbleSize - currentSize >= batchSize * bufferMemUnit) {
                return true;
            } else {
                return false;
            }
        }
    }

    private long calculateSize(Event event) {
        // 直接返回binlog中的事件大小
        return event.getEntry().getHeader().getEventLength();
    }

    private int getIndex(long sequcnce) {
        return (int) sequcnce & indexMask;
    }

    private boolean isDdl(EventType type) {
        return type == EventType.ALTER || type == EventType.CREATE || type == EventType.ERASE
               || type == EventType.RENAME || type == EventType.TRUNCATE || type == EventType.CINDEX
               || type == EventType.DINDEX;
    }

    // ================ setter / getter ==================

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public void setBufferMemUnit(int bufferMemUnit) {
        this.bufferMemUnit = bufferMemUnit;
    }

    public void setBatchMode(BatchMode batchMode) {
        this.batchMode = batchMode;
    }

    public void setDdlIsolation(boolean ddlIsolation) {
        this.ddlIsolation = ddlIsolation;
    }

}
