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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同步屏障
 * CyclicBarrier 的字面意思是可循环使用（Cyclic）的屏障（Barrier）。
 * 它要做的事情是，让一组线程到达一个屏障（也可以叫同步点）时被阻塞，直到最后一个线程到达屏障时，屏障才会开门，所有被屏障拦截的线程才会继续干活。
 */
public class CyclicBarrier {
    /**
     * 在CyclicBarrier中用Generation来代表每一轮的Cyclibarrier的运行状况。
     * 在任意时刻只有一个genration实例是真正代表当前这一轮的运行状况，其他实例都是跑完或者跑挂的
     */
    private static class Generation {
        /**
         * 屏障是否被打破的标识
         */
        boolean broken = false;
    }

    /**
     * 保护屏障入口的锁
     */
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * 线程等待的条件
     */
    private final Condition trip = lock.newCondition();
    /**
     * 总次数，调用parties次await()后屏障开启
     */
    private final int parties;

    /**
     * 当所有线程到达屏障点之后，首先执行的命令
     * 该操作由最后一个进入屏障点的线程执行
     */
    private final Runnable barrierCommand;
    /**
     * 找不到合适的词语形容它，
     * 每一轮都会生成一个新的generation
     */
    private Generation generation = new Generation();

    /**
     * 实际中仍在等待的线程数，每当有一个新的线程调用await()，count-1
     * 当一次新的一轮开始后，count的值被重置为parties。
     */
    private int count;

    /**
     * 开启下一轮
     */
    private void nextGeneration() {
        // 唤醒所有通过trip等待的线程
        trip.signalAll();
        // 重置count
        count = parties;
        // 生成新的generation
        generation = new Generation();
    }

    /**
     * 使当前generation处于打破状态，重置剩余count,并且唤醒状态变量
     */
    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        //唤醒所有等待中的线程，线程被唤醒后发现generation被打破，将抛出BrokenBarrierException
        trip.signalAll();
    }

    /**
     * 等待
     */
    private int dowait(boolean timed, long nanos)
            throws InterruptedException, BrokenBarrierException,
            TimeoutException {
        //拿到当前锁并上锁
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //当前generation
            final Generation g = generation;
            //如果本轮已经被打破，抛出异常
            if (g.broken)
                throw new BrokenBarrierException();
            //线程被中断
            if (Thread.interrupted()) {
                //执行打破策略：更新打破标识，重置count，并唤醒所有等待中的线程
                breakBarrier();
                throw new InterruptedException();
            }

            //count -1
            int index = --count;
            //count为0，需要执行唤醒操作了
            if (index == 0) {  // tripped
                //执行标识
                boolean ranAction = false;
                try {
                    final Runnable command = barrierCommand;
                    //如果存在barrierCommand，用当前线程执行
                    if (command != null)
                        //command.run()只是普通的调用run方法，并没有开线程调用，可能会抛异常
                        command.run();
                    //更新标志位
                    ranAction = true;
                    //开启下一轮：重置count，生成新的generation，并唤醒所有等待中的线程
                    //可以看出。如果当前线程是最后一个线程，是不需要进入等待队列的
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction)
                        //如果执行标识仍为false，说明执行barrierCommand过程中发生了异常，执行打破策略
                        breakBarrier();
                }
            }

            // 自旋直至被唤醒、超时、被打破
            for (; ; ) {
                try {
                    if (!timed) {
                        //没有设置超时时间，使用的是Condition.await()方法进行等待
                        trip.await();
                    } else if (nanos > 0L)
                        //awaitNanos返回的是剩余时间
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    //如果等待过程中发生中断
                    if (g == generation && !g.broken) {
                        //barrier没有被破坏，执行破坏策略
                        breakBarrier();
                        throw ie;
                    } else {
                        //被破坏了直接挂起
                        Thread.currentThread().interrupt();
                    }
                }

                //如果被破坏，抛出异常
                //可能是通过breakBarrier()唤醒的
                if (g.broken)
                    throw new BrokenBarrierException();

                //nextGeneration()对generation进行了更新，如果已经更新，返回，否则下一轮循环直至更新
                if (g != generation)
                    return index;

                //超时判断
                if (timed && nanos <= 0L) {
                    //超时执行破坏策略
                    breakBarrier();
                    //抛出TimeoutException，TimeoutException不是RuntimeException，需要手动catch
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 构造方法
     *
     * @param parties       等待线程数量
     * @param barrierAction 当线程到达屏障点时首先执行的action
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    /**
     * 构造方法
     *
     * @param parties 参与等待的线程数
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * 参与等待的线程数
     */
    public int getParties() {
        return parties;
    }

    /**
     * 等待
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            //本方法不会抛出TimeoutException，如果抛出肯定是出问题了
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * 限时等待
     *
     * @param timeout 超时时间
     * @param unit    单位
     * @return
     * @throws InterruptedException   线程中断时抛出
     * @throws BrokenBarrierException 屏障打破时抛出
     * @throws TimeoutException       超时时抛出
     */
    public int await(long timeout, TimeUnit unit)
            throws InterruptedException,
            BrokenBarrierException,
            TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * 当前屏障是否被打破
     *
     * @return
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置屏障
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //打破当前这轮
            breakBarrier();   // break the current generation
            //生成下一轮
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前等待唤醒的线程数
     *
     * @return
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
