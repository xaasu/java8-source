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
import java.util.Collection;

/**
 * A reentrant mutual exclusion {@link Lock} with the same basic
 * behavior and semantics as the implicit monitor lock accessed using
 * {@code synchronized} methods and statements, but with extended
 * capabilities.
 *
 * <p>A {@code ReentrantLock} is <em>owned</em> by the thread last
 * successfully locking, but not yet unlocking it. A thread invoking
 * {@code lock} will return, successfully acquiring the lock, when
 * the lock is not owned by another thread. The method will return
 * immediately if the current thread already owns the lock. This can
 * be checked using methods {@link #isHeldByCurrentThread}, and {@link
 * #getHoldCount}.
 *
 * <p>The constructor for this class accepts an optional
 * <em>fairness</em> parameter.  When set {@code true}, under
 * contention, locks favor granting access to the longest-waiting
 * thread.  Otherwise this lock does not guarantee any particular
 * access order.  Programs using fair locks accessed by many threads
 * may display lower overall throughput (i.e., are slower; often much
 * slower) than those using the default setting, but have smaller
 * variances in times to obtain locks and guarantee lack of
 * starvation. Note however, that fairness of locks does not guarantee
 * fairness of thread scheduling. Thus, one of many threads using a
 * fair lock may obtain it multiple times in succession while other
 * active threads are not progressing and not currently holding the
 * lock.
 * Also note that the untimed {@link #tryLock()} method does not
 * honor the fairness setting. It will succeed if the lock
 * is available even if other threads are waiting.
 *
 * <p>It is recommended practice to <em>always</em> immediately
 * follow a call to {@code lock} with a {@code try} block, most
 * typically in a before/after construction such as:
 *
 * <pre> {@code
 * class X {
 *   private final ReentrantLock lock = new ReentrantLock();
 *   // ...
 *
 *   public void m() {
 *     lock.lock();  // block until condition holds
 *     try {
 *       // ... method body
 *     } finally {
 *       lock.unlock()
 *     }
 *   }
 * }}</pre>
 *
 * <p>In addition to implementing the {@link Lock} interface, this
 * class defines a number of {@code public} and {@code protected}
 * methods for inspecting the state of the lock.  Some of these
 * methods are only useful for instrumentation and monitoring.
 *
 * <p>Serialization of this class behaves in the same way as built-in
 * locks: a deserialized lock is in the unlocked state, regardless of
 * its state when serialized.
 *
 * <p>This lock supports a maximum of 2147483647 recursive locks by
 * the same thread. Attempts to exceed this limit result in
 * {@link Error} throws from locking methods.
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    /**
     * AQS的之类，锁状态器
     */
    private final Sync sync;

    /**
     * Base of synchronization control for this lock. Subclassed
     * into fair and nonfair versions below. Uses AQS state to
     * represent the number of holds on the lock.
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;

        /**
         * 获得锁，抽象方法由子类实现
         */
        abstract void lock();

        /**
         * 非公平锁，状态为0尝试获取一次资源，失败进入队列
         */
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            } else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }

        /**
         * 释放锁
         *
         * @param releases
         * @return
         */
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            //如果当前线程不是持有锁的线程，抛异常
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {
                //已经没有线程占有锁了，完全释放
                free = true;
                setExclusiveOwnerThread(null);
            }
            //重入数-1
            setState(c);
            return free;
        }

        /**
         * 持有锁的线程是否是当前线程
         *
         * @return
         */
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        /**
         * 持有锁的线程
         *
         * @return
         */
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        /**
         * 锁的深度
         *
         * @return
         */
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        /**
         * 锁的状态
         *
         * @return
         */
        final boolean isLocked() {
            return getState() != 0;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         */
        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    /**
     * 非公平锁
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        /**
         * 加锁
         */
        final void lock() {
            //如果能直接更新state，那么就不需要进入队列了，否则进入队列
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                //调用aqs的acquire方法
                acquire(1);
        }

        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    /**
     * 公平锁
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        final void lock() {
            //因为公平锁需要对队列进行判断，故不能直接CAS更新state
            acquire(1);
        }

        /**
         * 公平锁，直接排队
         */
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            //state为0表示没有线程占用锁
            if (c == 0) {
                //如果队列前面没有元素，即只有当前线程在等待锁
                //公平锁会有此判断，非公平锁不会
                if (!hasQueuedPredecessors() &&
                        compareAndSetState(0, acquires)) {
                    //标识当前线程成功获得锁
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            //不为0则表示有线程占用了锁，需要判断是否是当前线程，如果是，则重入
            else if (current == getExclusiveOwnerThread()) {
                //增加重入次数
                int nextc = c + acquires;
                //次数值溢出，抛出异常
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            //其他线程占用了锁
            return false;
        }
    }

    /**
     * 无参构造方法，默认为非公平锁，因为公平锁需要判断队列，存在开销
     */
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    /**
     * 有参构造方法
     *
     * @param fair {@code true} 是否是公平锁
     */
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    /**
     * 获取锁，如果锁已经被其他线程占有，等待获取
     */
    public void lock() {
        sync.lock();
    }

    /**
     * 响应中断，如果得到锁后获取资源过程中线程中断，抛异常
     */
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    /**
     * 获取锁，如果锁已经被其他线程占用，返回false
     */
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
    }

    /**
     * 指定时间内获取锁，如果超过指定时间仍未获取成功返回false
     * 如果等待过程中线程被中断，抛异常
     */
    public boolean tryLock(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    /**
     * 释放锁
     */
    public void unlock() {
        sync.release(1);
    }

    /**
     * 新建一个Condition
     */
    public Condition newCondition() {
        return sync.newCondition();
    }

    /**
     * 当前锁的深度
     */
    public int getHoldCount() {
        return sync.getHoldCount();
    }

    /**
     * 是否是当前线程持有锁
     */
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 是否有线程占有锁
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * 是否是公平锁
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 获得持有锁的线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * 是否有线程在等待锁
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 判断线程是否在队列中
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * 队列长度
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 队列中的线程
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * Condition是否有线程在等待
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Condtion等待深度
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * 等待Condition的线程
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes either the String {@code "Unlocked"}
     * or the String {@code "Locked by"} followed by the
     * {@linkplain Thread#getName name} of the owning thread.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                "[Unlocked]" :
                "[Locked by thread " + o.getName() + "]");
    }
}
