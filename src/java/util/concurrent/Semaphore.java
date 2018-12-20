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

package java.util.concurrent;

import java.util.Collection;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 信号量
 */
public class Semaphore implements java.io.Serializable {
    private static final long serialVersionUID = -3222578661600680210L;
    /**
     * AQS状态器
     */
    private final Sync sync;

    /**
     * AQS状态器
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1192457210091910933L;

        /**
         * 构造方法
         *
         * @param permits
         */
        Sync(int permits) {
            //初始化凭证数量
            setState(permits);
        }

        /**
         * 获取剩余凭证数量
         *
         * @return
         */
        final int getPermits() {
            return getState();
        }

        /**
         * 非公平模式获取凭证
         *
         * @param acquires
         * @return 共享模式下获取资源返回值的约定：
         * 负值：获取失败；
         * 0：获取成功但没有剩余资源；
         * 正值：获取成功，有剩余资源，其他线程还可以获取
         */
        final int nonfairTryAcquireShared(int acquires) {
            for (; ; ) {
                //可用凭证数量
                int available = getState();
                //计算本次调用后剩余凭证
                int remaining = available - acquires;
                //剩余凭证不足直接返回，否则CAS更新剩余凭证数量
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    //返回剩余许可
                    return remaining;
            }
        }

        /**
         * 释放凭证
         *
         * @param releases
         * @return
         */
        protected final boolean tryReleaseShared(int releases) {
            //总体来说比较简单，就是更新state
            for (; ; ) {
                //当前可用凭证，拿来做CAS用的
                int current = getState();
                //释放之后的凭证数量，CAS需要更新的目标值
                int next = current + releases;
                //溢出抛出异常
                if (next < current) // overflow
                    throw new Error("Maximum permit count exceeded");
                //CAS成功返回，失败则说明其他线程也在释放，接着CAS
                if (compareAndSetState(current, next))
                    return true;
            }
        }

        /**
         * 减少凭证数量，某些场景可能凭证使用后需要销毁，可以用此方法永久减少
         *
         * @param reductions
         */
        final void reducePermits(int reductions) {
            //总体来说和tryReleaseShared差不多，不过一个加一个减
            for (; ; ) {
                //可以用凭证
                int current = getState();
                //减少之后的凭证数量
                int next = current - reductions;
                //溢出
                if (next > current) // underflow
                    throw new Error("Permit count underflow");
                if (compareAndSetState(current, next))
                    return;
            }
        }

        /**
         * 清空凭证
         *
         * @return
         */
        final int drainPermits() {
            for (; ; ) {
                //CAS将剩余凭证数量更新为0即可
                int current = getState();
                if (current == 0 || compareAndSetState(current, 0))
                    return current;
            }
        }
    }

    /**
     * 非公平
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -2694183684443567898L;

        NonfairSync(int permits) {
            super(permits);
        }

        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }

    /**
     * 公平
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = 2014338818796000944L;

        FairSync(int permits) {
            super(permits);
        }

        protected int tryAcquireShared(int acquires) {
            for (; ; ) {
                //先看当前是否有其他线程正在等待许可释放
                if (hasQueuedPredecessors())
                    return -1;
                //以下同非公平
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    return remaining;
            }
        }
    }

    /**
     * 构造方法
     *
     * @param permits 同一时间最多可以获取凭证的线程数
     */
    public Semaphore(int permits) {
        sync = new NonfairSync(permits);
    }

    /**
     * 构造方法
     *
     * @param permits 凭证数量
     * @param fair    是否公平
     */
    public Semaphore(int permits, boolean fair) {
        sync = fair ? new FairSync(permits) : new NonfairSync(permits);
    }

    /**
     * 获取1个凭证，如果凭证数量不够，等待其他线程释放
     *
     * @throws InterruptedException 等待过程中线程中断时抛出
     */
    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * 获取1个凭证，不响应中断
     */
    public void acquireUninterruptibly() {
        sync.acquireShared(1);
    }

    /**
     * 尝试获取凭证，立即返回结果
     *
     * @return
     */
    public boolean tryAcquire() {
        return sync.nonfairTryAcquireShared(1) >= 0;
    }

    /**
     * 尝试获取凭证，限时等待
     *
     * @param timeout
     * @param unit
     * @return 超时或者获取失败返回false
     * @throws InterruptedException 等待过程中线程中断时抛出
     */
    public boolean tryAcquire(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * 释放一个凭证
     */
    public void release() {
        sync.releaseShared(1);
    }

    /**
     * 获取多个凭证，如果凭证不够则等待至有其他线程释放凭证到够为止
     *
     * @param permits
     * @throws InterruptedException
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireSharedInterruptibly(permits);
    }

    /**
     * 获取多个凭证，不响应中断
     *
     * @param permits
     */
    public void acquireUninterruptibly(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireShared(permits);
    }

    /**
     * 尝试获取凭证，立即返回结果
     *
     * @param permits
     * @return
     */
    public boolean tryAcquire(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.nonfairTryAcquireShared(permits) >= 0;
    }

    /**
     * 尝试获取凭证，限时等待
     *
     * @param permits
     * @param timeout
     * @param unit
     * @return 获取失败或者超时返回false
     * @throws InterruptedException 等待过程中线程中断时抛出
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.tryAcquireSharedNanos(permits, unit.toNanos(timeout));
    }

    /**
     * 释放多个凭证
     *
     * @param permits
     */
    public void release(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.releaseShared(permits);
    }

    /**
     * 剩余可用凭证数量
     *
     * @return
     */
    public int availablePermits() {
        return sync.getPermits();
    }

    /**
     * 清空凭证，清空后可用凭证为0
     *
     * @return
     */
    public int drainPermits() {
        return sync.drainPermits();
    }

    /**
     * 减少凭证
     *
     * @param reduction 要减少的数量
     */
    protected void reducePermits(int reduction) {
        if (reduction < 0) throw new IllegalArgumentException();
        sync.reducePermits(reduction);
    }

    /**
     * 是否是公平模式
     *
     * @return
     */
    public boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 是否有线程在等待许可
     *
     * @return
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 等待队列长度
     *
     * @return
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 等待队列中的线程
     *
     * @return
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * Returns a string identifying this semaphore, as well as its state.
     * The state, in brackets, includes the String {@code "Permits ="}
     * followed by the number of permits.
     *
     * @return a string identifying this semaphore, as well as its state
     */
    public String toString() {
        return super.toString() + "[Permits = " + sync.getPermits() + "]";
    }
}
