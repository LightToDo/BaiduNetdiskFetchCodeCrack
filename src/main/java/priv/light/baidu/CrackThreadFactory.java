package priv.light.baidu;

import lombok.NonNull;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Light
 * @date 2022/4/7 11:56
 */
public class CrackThreadFactory implements ThreadFactory {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final String THREAD_POOL_NAME = "Crack Thread Pool-Thread-";

    @Override
    public Thread newThread(@NonNull Runnable r) {
        String threadName = THREAD_POOL_NAME + COUNTER.getAndIncrement();
        Thread thread = new Thread(r, threadName);
        thread.setDaemon(true);

        return thread;
    }

}
