/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import sun.misc.Unsafe;

/**
 * CLH也是一种基于单向链表(隐式创建)的高性能、公平的自旋锁，申请加锁的线程只需要在<b>其前驱节点的本地变量上</b>自旋，从而极大地减少了不必要的处理器缓存同步的次数，降低了总线和内存的开销。
 */

/**
 * AQS依赖同步队列（一个FIFO双向队列）来完成同步状态的管理。
 * 当前线程获取同步状态失败时，AQS会将当前线程以及等待状态等信息构造成一个节点(Node)并且将其加入到同步队列中，同时会阻塞当前线程
 * 当同步状态释放时，会把首节点中的线程唤醒，使其再次尝试获取同步状态。
 */
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() {
    }

    /**
     * 等待队列，是一个双向链表，每个线程进入AQS等待队列都会被包装成一个Node节点
     */
    static final class Node {
        /**
         * 共享节点
         */
        static final Node SHARED = new Node();
        /**
         * 独占节点
         */
        static final Node EXCLUSIVE = null;

        /**
         * 由于超时或中断，节点已被取消，进入该状态后的结点将不会再变化。
         */
        static final int CANCELLED = 1;
        /**
         * <b>下一个节点</b>是通过park阻塞的，需要通过unpark唤醒
         */
        static final int SIGNAL = -1;
        /**
         * 表示线程在等待条件变量
         */
        static final int CONDITION = -2;
        /**
         * 表示后续结点会传播唤醒的操作，共享模式下起作用
         */
        static final int PROPAGATE = -3;

        /**
         * 状态字段，仅接受值:
         * <p>
         * SIGNAL:值为-1 ，后继节点的线程处于等待状态，
         * 而当前节点的线程如果释放了同步状态或者被取消，
         * 将会通知后继节点，使后继节点的线程得以运行。
         * <p>
         * CANCELLED:值为1，由于在同步队列中等待的线程等待超时或者被中断，
         * 需要从同步队列中取消等待，节点进入该状态将不会变化
         * <p>
         * CONDITION: 值为-2，节点在等待队列中，
         * 节点线程等待在Condition上，当其他线程
         * 对Condition调用了singal方法后，该节点
         * 将会从等待队列中转移到同步队列中，加入到
         * 对同步状态的获取中
         * <p>
         * PROPAGATE: 值为-3，表示下一次共享模式同步
         * 状态获取将会无条件地传播下去
         * <p>
         * INITIAL: 初始状态值为0
         */
        volatile int waitStatus;

        /**
         * 链接到前驱节点，当前节点/线程依赖它来检查waitStatus。
         * 在入同步队列时被设置，并且仅在移除同步队列时才归零（为了GC的目的）。
         * 此外，在取消前驱节点时，我们在找到未取消的一个时进行短路，
         * 这将始终存在，因为头节点从未被取消：节点仅作为成功获取的结果而变为头。
         * 被取消的线程永远不会成功获取，并且线程只取消自身，而不是任何其他节点。
         */
        volatile Node prev;

        /**
         * 链接到后续节点，当前节点/线程释放时释放。
         * 在入同步队列期间分配，在绕过取消的前驱节点时调整，并在出同步队列时取消（为了GC的目的）。
         * enq操作不会分配前驱节点的next字段，直到附加之后，
         * 因此看到一个为null的next字段不一定意味着该节点在队列的末尾。
         * 但是，如果next字段显示为null,我们可以从尾部扫描prev，仔细检查。
         * 被取消的节点的next字段被设置为指向节点本身而不是null，以使isOnSyncQueue更方便操作。
         * 调用isOnSyncQueue时，如果节点（始终是放置在条件队列上的节点）正等待在同步队列上重新获取，则返回true。
         **/
        volatile Node next;

        /**
         * 节点所包装的线程.
         */
        volatile Thread thread;

        /**
         * 除了Condition，其他情况下用来判断是否是共享模式
         */
        Node nextWaiter;

        /**
         * 是否是共享模式.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * 获得前驱节点
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            //因为Node队列是根据节点的前驱节点来判断的，所以如果前驱节点为空，抛异常
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        /**
         * 由addWaiter()调用
         *
         * @param thread
         * @param mode
         */
        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        /**
         * 供Condition使用
         */
        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * 头结点，也是获取资源成功的节点
     */
    private transient volatile Node head;

    /**
     * 尾结点，没有成功获取到同步状态的线程将放入尾部
     */
    private transient volatile Node tail;

    /**
     * 同步器状态，从使用情况来看，多是给子类调用
     */
    private volatile int state;

    /**
     * 获得当前状态
     *
     * @return 当state>0时表示已经获取了锁，当state = 0时表示释放了锁
     */
    protected final int getState() {
        return state;
    }

    /**
     * 更新同步器状态
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * CAS更新同步器状态
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // 调用unsafe更新
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * 用于做限时等待的时间因子，当剩余时间小于这个值时，判线程获取资源失败，不作堵塞
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * 将目标节点插入至队列尾部
     */
    private Node enq(final Node node) {
        for (; ; ) {
            //自旋，直到目标节点插入
            Node t = tail;
            if (t == null) { // Must initialize
                //如果尾节点为空，说明当前队列是空的，CAS初始化队列
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                //将旧的尾节点设为目标节点的前驱
                node.prev = t;
                //CAS设置目标节点为尾节点
                if (compareAndSetTail(t, node)) {
                    //原尾节点后继节点设置为目标节点
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * 将该线程加入等待队列的尾部，并标记为独占模式
     *
     * @param mode，独占模式还是共享模式
     * @return
     */
    private Node addWaiter(Node mode) {
        //新进来的线程都被包装成一个新的节点
        Node node = new Node(Thread.currentThread(), mode);
        // 获取当前尾节点
        Node pred = tail;
        if (pred != null) {
            //尾部节点不为空，设置当前节点前驱节点
            node.prev = pred;
            //CAS设置当前节点为尾节点
            if (compareAndSetTail(pred, node)) {
                //之前的尾节点后继设置为当前节点
                pred.next = node;
                return node;
            }
        }
        //FIXME 上面这部分代码在enq方法中也有，为什么要单独跑一遍，失败了再进enq？

        //尾节点为空，自旋插入，直到插入成功
        enq(node);
        return node;
    }

    /**
     * 更新头结点，线程不安全的
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * 唤醒等待队列中的下一个线程
     * <p>
     * 用unpark()唤醒等待队列中最前边的那个未放弃线程
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus;
        if (ws < 0)
            //更新当前线程状态，允许失败
            //FIXME 为什么要更新为0？
            compareAndSetWaitStatus(node, ws, 0);

        //下一个需要唤醒的节点
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            //为空或者已取消，即节点失效
            s = null;
            //从队列尾部开始，循环直到离node最近的有效节点，有效的定义：状态<=0
            //FIXME 为什么要从尾部开始？
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null)
            //唤醒该节点
            LockSupport.unpark(s.thread);
    }

    /**
     * 唤醒队列中等待的节点
     * 这个方法看不懂
     */
    private void doReleaseShared() {
        //自旋唤醒，直到没有可唤醒的为止
        for (; ; ) {
            Node h = head;
            //不是尾节点
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    //只有当头结点状态为-1，才能唤醒后继节点
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
                        //更新节点状态失败，别的线程已经更新了头结点，重试
                        continue;            // loop to recheck cases
                    }
                    //唤醒后继节点
                    //要注意，unparkSuccessor()后，后继节点被唤醒，则有另一个线程被唤醒，该线程执行的代码会继续执行，那么head就有可能会改变
                    unparkSuccessor(h);
                }
                //为什么要更新成-3？因为共享模式下更多使用PROPAGATE来传播
                //为什么不直接在上面就更新成-3，因为unparkSuccessor(h)会把头结点更新为0
                else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    //更新节点状态失败，别的线程已经更新了头结点，重试
                    continue;                // loop on failed CAS
            }
            //头结点没变，状态成功变成-3，所有节点都被唤醒
            //头结点改变，继续唤醒，直到所有节点都被唤醒
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * 设置头结点，并根据剩余资源量继续唤醒后继线程
     *
     * @param node      已经获取资源的目标节点
     * @param propagate 剩余可用资源数
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        //旧的头结点
        Node h = head; // Record old head for check below
        //设置当前节点为头结点
        setHead(node);

        //这里意思有两种情况是需要执行唤醒操作
        //1.propagate > 0 表示调用方指明了后继节点需要被唤醒
        //2.头节点后面的节点需要被唤醒（waitStatus<0），不论是老的头结点还是新的头结点
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            //如果当前节点的后继节点是共享类型或者没有后继节点，则进行唤醒
            //这里可以理解为除非明确指明不需要唤醒（后继等待节点是独占类型），否则都要唤醒
            if (s == null || s.isShared())
                //唤醒
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire

    /**
     * 取消当前节点
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        //释放节点对应的线程
        node.thread = null;

        Node pred = node.prev;
        //找到最近的一个有效节点，设置为目标节点的前驱节点
        while (pred.waitStatus > 0)
            //等价于：
            //node.prev=pred.prev;
            //pred=pred.prev
            node.prev = pred = pred.prev;

        //前面最近一个有效的节点的next，有可能是node也有可能不是node
        Node predNext = pred.next;

        //将目标节点的状态设置为1
        node.waitStatus = Node.CANCELLED;

        //如果目标节点是尾节点，把pred设置为新的尾节点并释放目标节点
        if (node == tail && compareAndSetTail(node, pred)) {
            //新的尾节点next设置为空
            compareAndSetNext(pred, predNext, null);
        } else {
            //node不是尾节点
            int ws;
            //pred不是头结点，并且节点状态有效
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                //上面的CAS判断已经将pred状态更新为-1
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    //将pred的next设置为目标节点的next
                    //next.prev还是node，在其他代码中都有对前驱节点状态的判断，如果是cancelled，会维护队列关系
                    //比方说shouldParkAfterFailedAcquire
                    compareAndSetNext(pred, predNext, next);
            } else {
                //唤醒next
                unparkSuccessor(node);
            }
            //将node.next的引用关系设为自身，便于GC
            //FIXME 为什么不设置为null？
            node.next = node; // help GC
        }
    }

    /**
     * 判断是否可以挂起当前线程
     *
     * @param pred : 前驱节点
     * @param node : 目标节点
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        //前驱节点的状态
        int ws = pred.waitStatus;
        //如果前驱节点状态是SIGNAL，那么可以挂起了
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;
        if (ws > 0) {
            //前驱节点已经取消
            do {
                //一直往前找，找到一个状态有效的节点为止，如何判断状态有效：waitStatus<=0
                //等价于：
                //node.prev=pred.prev;
                //pred=pred.prev;
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            //将该节点的后继节点设置为next，建立关系
            pred.next = node;
        } else {
            //如果前驱正常，把前驱状态设置为SIGNAL，因为会做这个判断，说明是想挂起当前节点的
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        //如果前驱节点不是SIGNAL,那么就不能挂起，等待下一次轮询，直至前驱状态为SIGNAL
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        //中断当前线程
        Thread.currentThread().interrupt();
    }

    /**
     * 使用LockSupport挂起当前线程
     */
    private final boolean parkAndCheckInterrupt() {
        //执行完park后，代码被阻塞
        LockSupport.park(this);
        //只有线程被唤醒，代码才会继续往下走
        // 如何唤醒线程？一是unpark()，二是线程被中断
        // 代码才会继续执行，返回Thread.interrupted()用于判断是否曾今被挂起
        //interrupted()：返回中断状态，之后会清除中断状态
        return Thread.interrupted();
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * 使线程在等待队列中获取资源，一直获取到资源后才返回。
     * 如果在整个等待过程中被中断过，则返回true，否则返回false。
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            //自旋操作
            for (; ; ) {
                //获取当前节点的前驱节点
                final Node p = node.predecessor();

                //只有前驱节点是头结点才有资格获取资源，尝试获取
                if (p == head && tryAcquire(arg)) {
                    //获取资源成功，更新当前节点为头结点，释放原来的头节点
                    setHead(node);
                    //前head节点后继设置为空，便于GC
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                //挂起线程，等待唤醒，唤醒后再次进入循环，判断是否有资格拿到当前资源
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    //如果线程曾今被挂起，interrupted返回为true
                    interrupted = true;
                //执行到这的时候，线程被唤醒，进行下一轮循环
            }
        } finally {
            //发生异常了
            if (failed)
                //出现异常，取消当前节点并从队列中清除
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     *
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                //和acquireQueue()基本一致，唯一区别就是如果线程中断过，抛出异常
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 限时等待
     *
     * @param arg          the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        //超时时间
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                //获得资源成功
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                //剩余时间
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                //阻塞，等待唤醒
                //spinForTimeoutThreshold:一个时间因子，如果剩余时间小于此值，就不做阻塞了，因为spinForTimeoutThreshold值并不大
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    //parkNanos限时阻塞
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 将当前线程加入等待队列尾部等待唤醒，拿到资源后返回
     */
    private void doAcquireShared(int arg) {
        //加入一个共享模式的节点
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (; ; ) {
                //前驱节点
                final Node p = node.predecessor();
                if (p == head) {
                    //前驱已经拿到资源，自己有资格获取资源，尝试获取
                    int r = tryAcquireShared(arg);
                    //成功获取
                    if (r >= 0) {
                        //将head指向自己，还有剩余资源可以再唤醒之后的线程，让后续线程尝试获取资源
                        setHeadAndPropagate(node, r);
                        //原来的head后继节点置空，确保下次GC被回收
                        p.next = null; // help GC
                        if (interrupted)
                            //更新中断状态
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                //挂起线程，等待唤醒
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 共享模式获取资源-响应中断
     *
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                //和doAcquireShared()代码类似，不过就是阻塞过程中发生中断，响应中断
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 共享模式限时等待
     *
     * @param arg          the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * 独占模式下尝试获取资源，子类实现，否则直接抛异常
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占模式下尝试释放资源，由子类实现细节
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享模式下获取资源
     *
     * @param arg
     * @return 负值：获取失败；
     * 0：获取成功但没有剩余资源；
     * 正值：获取成功，有剩余资源，其他线程还可以获取
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享模式下释放资源
     *
     * @param arg
     * @return 如果释放后允许唤醒后续等待结点返回true，否则返回false
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 当前同步器是否在独占式模式下被线程占用，一般该方法表示是否被当前线程所独占。只有用到condition才需要去实现它。
     * 给Condition使用具体实现见{@link ReentrantLock.Sync#isHeldByCurrentThread()}
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占模式获取同步资源
     *
     * @param arg
     */
    public final void acquire(int arg) {

        //tryAcquire()成功，说明获取资源成功，直接结束
        //否则调用addWaiter往队列中添加一个独占模式的节点
        //acquireQueued等待节点被唤醒，返回true表示线程发生过中断，响应中断
        if (!tryAcquire(arg) &&
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            //线程是被中断唤醒的，此时需要挂起当前线程
            selfInterrupt();
        //到这里，线程必定获得资源成功，否则一直阻塞
    }

    /**
     * 可中断式获取锁，逻辑和 acquire差不多唯一区别是中断的话抛异常
     *
     * @param arg
     * @throws InterruptedException 如果线程获取资源中发生了中断，抛出
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * 限时等待获取资源
     *
     * @param arg
     * @param nanosTimeout
     * @return
     * @throws InterruptedException
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
                doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * 独占模式下释放资源
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0)
                //唤醒头结点的后继节点
                //这边不更新head节点为后继节点是因为acquire()方法唤醒后会更新head节点
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * 共享模式下获取资源，和acquire差不多，只不过自己拿到资源后还会唤醒后继节点继续获取
     * tryAcquireShared()尝试获取资源，成功则直接返回；
     * 失败则通过doAcquireShared()进入等待队列park()，直到被unpark()/interrupt()并成功获取到资源才返回。整个等待过程也是忽略中断的
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            //失败，进入等待队列，直到获取到资源为止
            doAcquireShared(arg);
        //代码执行到这里说明已经拿到资源
    }

    /**
     * 共享模式下获取资源响应中断
     *
     * @param arg the acquire argument.
     *            This value is conveyed to {@link #tryAcquireShared} but is
     *            otherwise uninterpreted and can represent anything
     *            you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg          the acquire argument.  This value is conveyed to
     *                     {@link #tryAcquireShared} but is otherwise uninterpreted
     *                     and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
                doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * 共享模式下释放资源
     */
    public final boolean releaseShared(int arg) {
        //尝试释放资源
        if (tryReleaseShared(arg)) {
            //唤醒队列中等待的节点
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
                s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null &&
                        s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
                (s = h.next) != null &&
                !s.isShared() &&
                s.thread != null;
    }

    /**
     * 判断当前线程是否在队列最前面（1号节点，因为head节点不包含线程信息）
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * 判断节点是否在AQS队列中(不是Condition队列)
     *
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        //Condition队列的节点没有设置前驱节点，并且状态为2，只要满足一点，便说明节点还在Condition队列中
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        //既然节点不在Condition队列中，那么如果节点后继节点不为空，必然在AQS队列了
        if (node.next != null) // If has successor, it must be on queue
            return true;
        //代码走到这里还没返回，遍历整个队列，看是否能找到node
        return findNodeFromTail(node);
    }

    /**
     * 判断节点是否在AQS队列中
     *
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        //从尾节点开始遍历
        for (; ; ) {
            if (t == node)
                return true;
            //到头结点还没找到node
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * 将节点加入AQS队列
     *
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /*
         * 节点已取消
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        //将node加入AQS队列，p是原来的尾节点
        Node p = enq(node);
        int ws = p.waitStatus;
        //p已经取消，或者CAS更新p为-1失败，直接唤醒
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            //唤醒目标线程
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * Condition节点因为超时或者中断被取消，加入AQS队列等待唤醒
     *
     * @param node the node
     * @return 线程是否因为中断从park中唤醒
     */
    final boolean transferAfterCancelledWait(Node node) {
        //中断发生时，是否有signal操作来“掺和”来返回结果，因为signal()也会将node插入AQS队列

        //CAS修改节点状态成功说明没有signal()发生
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            //将节点加入队列
            enq(node);
            return true;
        }
        //CAS失败说明可能有signal()发生，在返回false之前，需要做等待处理，等待signal将node插入AQS队列
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    /**
     * 释放锁独占锁
     *
     * @param node the condition node for this wait
     * @return previous sync state 释放之前的state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            //释放锁
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                //释放失败抛异常
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                //失败将waitStatus状态更新为1
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * 判断Condition是否由当前AQS同步器创建
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * 判断是否有线程在等待Condition
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Condition队列长度
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Condition队列中的等待线程
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * 条件队列
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /**
         * 头节点
         */
        private transient Node firstWaiter;
        /**
         * 尾节点
         */
        private transient Node lastWaiter;

        /**
         * 无参构造器
         */
        public ConditionObject() {
        }

        // Internal methods

        /**
         * 往Condition等待队列中新增节点,等待队列是一个单链表，只维护了后继，没有设置前驱
         */
        private Node addConditionWaiter() {
            //Condition对队列的操作没考虑并发，因为对应的操作都是在线程获得锁之后才进行的

            //尾节点
            Node t = lastWaiter;
            // 尾节点状态不是-2，说明无效，清除
            //Condition里面的节点状态只能是0和-2，否则就是无效节点
            if (t != null && t.waitStatus != Node.CONDITION) {
                //清除队列中无效的节点
                unlinkCancelledWaiters();
                //重新将新的尾节点赋值给t
                t = lastWaiter;
            }
            //新建一个当前节点
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            //如果尾节点为空
            if (t == null)
                //初始化队列，当前节点设为头结点
                firstWaiter = node;
            else
                //原尾节点的后继节点设置为当前节点
                t.nextWaiter = node;
            //当前节点设置为新的尾节点
            lastWaiter = node;
            return node;
        }

        /**
         * 唤醒Condition队列中的第一个节点
         *
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                //下面的操作是把first从队列移除
                if ((firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
            //transferForSignal(first)为true，退出循环，说明节点已经唤醒
            //否则把first赋值给下个节点继续尝试唤醒，直到有一个节点被唤醒，或者所有节点都唤醒失败
        }

        /**
         * 唤醒所有Condition队列中的节点
         *
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            //和doSignal()类似，只不过doSignal()是唤醒一个就退出循环，而doSignalAll是唤醒所有，直到Condition队列为空
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * 清空队列中的无效节点，无效节点的定义：状态不是-2
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            //上一个被遍历的节点
            Node trail = null;
            //从头结点开始遍历队列，移除无效节点
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    //t无效，设置后继节点为null，便于gc
                    t.nextWaiter = null;
                    //trail未被赋值，说明前面没节点，直接将头结点设为next
                    if (trail == null)
                        firstWaiter = next;
                    else
                        //trail的后继节点设置为next
                        trail.nextWaiter = next;
                    //next为null，说明到尾节点了
                    if (next == null)
                        lastWaiter = trail;
                } else
                    //t有效，跳过
                    trail = t;
                t = next;
            }
        }

        // public methods

        /**
         * 唤醒一个等待唤醒的线程，这里的唤醒，线程不会立刻苏醒，因为线程需要重新获得锁才可以真正的苏醒
         * 因此此方法要做的是将节点移至AQS等待队列
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        public final void signal() {
            //如果持有锁的线程不是当前线程
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            //唤醒的其实是Condition队列中的第一个节点
            Node first = firstWaiter;
            if (first != null)
                //唤醒
                doSignal(first);
        }

        /**
         * 唤醒所有等待节点，效果同signal()，将所有的Condition队列中的节点转移至AQS队列等待真正的唤醒
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * 当前线程进入等待状态直到被通知，在此过程中对中断信号不敏感，不支持中断当前线程
         */
        public final void awaitUninterruptibly() {
            //将节点加入Condition队列
            Node node = addConditionWaiter();
            //释放当前锁
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            //和await()区别是线程中断不会退出循环
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                //如果线程曾今被中断
                if (Thread.interrupted())
                    interrupted = true;
            }
            //恢复之前的锁状态并相应中断
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /**
         * 线程在等待过程中发生了中断，但不需要抛出异常
         */
        private static final int REINTERRUPT = 1;
        /**
         * 线程在等待过程中发生了中断，且需要抛出异常
         */
        private static final int THROW_IE = -1;

        /**
         * 判断线程在等待过程中是否被中断
         */
        private int checkInterruptWhileWaiting(Node node) {
            //如果线程被中断，调用transferAfterCancelledWait()将节点转移至AQS队列
            //未被中断直接返回0
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * 当前线程进入等待状态直到被通知或者被中断
         *
         * @throws InterruptedException 如果中断，抛出
         */
        public final void await() throws InterruptedException {
            //判断线程是否曾经中断
            if (Thread.interrupted())
                throw new InterruptedException();
            //将线程加入Condition的等待队列并返回生成的节点
            Node node = addConditionWaiter();
            //fullRelease会调用release释放原有的锁，如果没有锁，将会报错，因此使用await的前提就是线程已经得到独占锁
            //记录之前的state，唤醒后需要恢复状态，不然后续unlock()将报错
            int savedState = fullyRelease(node);

            int interruptMode = 0;
            //node节点不在AQS队列中，说明还没被signal()
            while (!isOnSyncQueue(node)) {
                //阻塞线程，等待唤醒
                LockSupport.park(this);
                //代码走到这说明线程被唤醒，可能是其他线程唤醒，也可能是中断唤醒
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    //线程被中断过，退出自旋
                    break;
            }
            //将node节点加入等待队列并等待获得资源，恢复await前的状态
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                //返回true说明线程被中断了，更新状态，后面补上中断处理
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // clean up if cancelled
                //清除无效节点，其实node已经无效了，因为已经唤醒了接下来要做的事情是AQS，与Condition无关了
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                //响应中断
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * 当前线程进入等待状态，直到被通知、中断或者超时。如果返回值小于等于0，可以认定就是超时了
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            //加入Condition队列
            Node node = addConditionWaiter();
            //释放锁
            int savedState = fullyRelease(node);
            //过期时间
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                //剩余时间小于0，取消节点
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                //剩余时间大于时间因此
                if (nanosTimeout >= spinForTimeoutThreshold)
                    //阻塞指定时间
                    LockSupport.parkNanos(this, nanosTimeout);
                //判断线程是否被中断
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            //响应中断
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            //返回耗时
            return deadline - System.nanoTime();
        }

        /**
         * 当前线程进入等待状态，直到被通知、中断或者超时。
         * 代码基本和awaitNanos一致
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * 当前线程进入等待状态，直到被通知、中断或者超时。
         * *代码基本和awaitNanos一致
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * 判断Condition是否属于sync.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * 判断Condition队列是否有线程在等待
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * 获得Condition队列的长度
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * 获得Condition队列中的线程
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("next"));

        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
