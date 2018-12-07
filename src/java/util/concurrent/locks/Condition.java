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
import java.util.Date;

/**
 * 条件，控制线程释放锁, 然后进行等待其他获取锁的线程发送 signal 信号来进行唤醒
 * 相当于Object.wait()/notify()
 */
public interface Condition {

    /**
     * 当前线程进入等待状态直到被通知或者被中断
     *
     * @throws InterruptedException 如果中断，抛出
     */
    void await() throws InterruptedException;

    /**
     * 当前线程进入等待状态直到被通知，在此过程中对中断信号不敏感，不支持中断当前线程
     */
    void awaitUninterruptibly();

    /**
     * 当前线程进入等待状态，直到被通知、中断或者超时。如果返回值小于等于0，可以认定就是超时了
     *
     * @throws InterruptedException 如果中断，抛出
     */
    long awaitNanos(long nanosTimeout) throws InterruptedException;

    /**
     * 同上
     *
     * @param time
     * @param unit
     * @return 如果指定时间内被通知，返回true，否则返回false
     * @throws InterruptedException
     */
    boolean await(long time, TimeUnit unit) throws InterruptedException;

    /**
     * 当前线程进入等待状态，直到被通知、中断或者超时。
     *
     * @param deadline 指定时间
     * @return 如果没到指定时间被通知，则返回true，否则返回false
     * @throws InterruptedException 线程发生中断，抛出
     */
    boolean awaitUntil(Date deadline) throws InterruptedException;

    /**
     * 唤醒一个等待在Condition上的线程，被唤醒的线程在方法返回前必须获得与Condition对象关联的锁
     */
    void signal();

    /**
     * 唤醒所有等待在Condition上的线程，能够从await()等方法返回的线程必须先获得与Condition对象关联的锁
     */
    void signalAll();
}
