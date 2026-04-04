package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@NullMarked
class WaitForSiblings {

    private final CountDownLatch allDoneReplayingLatch = new CountDownLatch(1);
    private final ReentrantLock lock = new ReentrantLock();

    @Nullable private final Semaphore maxParallelismSemaphore;
    private int yielded = 0;
    private final int total;

    WaitForSiblings(@Nullable Semaphore maxParallelismSemaphore, int total) {
        this.maxParallelismSemaphore = maxParallelismSemaphore;
        this.total = total;
    }

    public void waitForSiblingsDoneReplaying() {
        yieldFork();

        if (maxParallelismSemaphore != null) {
            maxParallelismSemaphore.release();
        }

        try {
            allDoneReplayingLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (maxParallelismSemaphore != null) {
            maxParallelismSemaphore.acquireUninterruptibly();
        }
    }

    public void yieldFork() {
        if (yielded == total) {
            return;
        }

        lock.lock();
        try {
            yielded += 1;
            if (yielded == total) {
                allDoneReplayingLatch.countDown();
            }
        } finally {
            lock.unlock();
        }
    }
}
