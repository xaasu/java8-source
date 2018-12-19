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

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 倒计时器
 * 初始化时需要设定凭证数量，线程调用await()方法进入等待状态，其他线程调用countDown()方法消耗凭证，当凭证消耗完毕后，唤醒等待状态的线程
 */
public class CountDownLatch {
    /**
     * AQS状态器，是一个共享锁
     */
    private static final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 4982264981922014374L;

        Sync(int count) {
            //初始化state，这里的state与重入锁的state虽然是同一个字段，但是含义有差别
            //重入锁state表示的是锁的深度，线程每重入一次，state+1
            // 而倒计时器，state表示的是资源剩余数量，每个线程调用await消耗一个
            setState(count);
        }

        int getCount() {
            return getState();
        }

        protected int tryAcquireShared(int acquires) {
            //tryAcquireShared约定返回值含义： 负值：获取失败；0：获取成功但没有剩余资源；正值：获取成功，有剩余资源，其他线程还可以获取
            //只有队列凭证为0的时候，才会返回1，因为倒计时器需要做的事情就是阻塞线程直到凭证为0才唤醒
            //因此只要state大于0，返回-1，阻塞线程
            return (getState() == 0) ? 1 : -1;
        }

        protected boolean tryReleaseShared(int releases) {
            //tryReleaseShared约定的返回值含义： 如果释放后允许唤醒后续等待结点返回true，否则返回false
            for (; ; ) {
                int c = getState();
                if (c == 0) {
                    //如果会进这说明CAS失败，别的线程已经给state改为0，那么该线程会返回true唤醒后续节点，本次就不需要返回true了
                    return false;
                }
                //state-1
                int nextc = c - 1;
                if (compareAndSetState(c, nextc))
                    //大于0不需要唤醒，还未到达唤醒条件，上面CAS已经给state-1了
                    //如果为0，说明凭证已经消耗完毕，那么接下来需要唤醒等待线程了
                    return nextc == 0;
            }
        }
    }

    private final Sync sync;

    /**
     * 构造方法，需要传入凭证数量
     */
    public CountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        //初始化sync，sync继承了AQS，实现了共享模式相关模版方法
        this.sync = new Sync(count);
    }

    /**
     * 线程调用调用await()方法，等待唤醒
     *
     * @throws InterruptedException 等待过程中中断时抛出
     */
    public void await() throws InterruptedException {
        //调用AQS的acquireSharedInterruptibly获取凭证
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * 超时等待
     *
     * @param timeout
     * @param unit
     * @return 为true说明凭证已经消耗完毕，超时返回false
     * @throws InterruptedException 等待过程中发生中断时抛出
     */
    public boolean await(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * 消耗凭证
     */
    public void countDown() {
        sync.releaseShared(1);
    }

    /**
     * Returns the current count.
     *
     * <p>This method is typically used for debugging and testing purposes.
     *
     * @return the current count
     */
    public long getCount() {
        return sync.getCount();
    }

    /**
     * Returns a string identifying this latch, as well as its state.
     * The state, in brackets, includes the String {@code "Count ="}
     * followed by the current count.
     *
     * @return a string identifying this latch, as well as its state
     */
    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }
}
