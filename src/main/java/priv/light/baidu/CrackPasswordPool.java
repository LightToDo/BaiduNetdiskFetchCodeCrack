package priv.light.baidu;

import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Light
 * @date 2022/3/30 21:37
 */

@Slf4j
@Data
public class CrackPasswordPool implements ThreadFactory, Runnable {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final String THREAD_POOL_NAME = "Crack Thread Pool-Thread-";
    public static final int CORE_POOL_SIZE;
    public static final int MAX_POOL_SIZE;

    static {
        CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() + 1;
        MAX_POOL_SIZE = 2 * CORE_POOL_SIZE;
    }

    private final AtomicBoolean threadShutdown;
    private final ThreadPoolExecutor threadPool;

    private final PasswordDictionary passwordHasTestDictionary;
    private final Set<String> passwords;
    private final Set<String> hasTestPasswords;
    private HttpUtil httpUtil;
    private final AtomicReference<String> truePassword;

    public CrackPasswordPool(@NonNull File passwordFile, @NonNull File hasTestPasswordFile) throws IOException {
        this.threadShutdown = new AtomicBoolean();

        int count = 4;
        PasswordDictionary passwordDictionary = new PasswordDictionary(count, passwordFile);
        this.passwordHasTestDictionary = new PasswordDictionary(count, hasTestPasswordFile);

        Set<String> passwords;
        if (!passwordFile.exists() || passwordFile.length() == 0) {
            passwordDictionary.writeToFile();
        }
        passwords = passwordDictionary.readPassword();
        passwordDictionary.dispose();

        if (hasTestPasswordFile.exists() && hasTestPasswordFile.length() > 0) {
            Set<String> hasTestPasswords = this.passwordHasTestDictionary.readPassword();
            passwords.removeAll(hasTestPasswords);
        }
        this.passwords = Collections.synchronizedSet(passwords);
        this.hasTestPasswords = Collections.synchronizedSet(new HashSet<>());

        int keepAliveTime = 1;

        BlockingQueue<Runnable> blockingQueue = new LinkedBlockingQueue<>(Integer.MAX_VALUE / 100);
        ThreadPoolExecutor.CallerRunsPolicy callerRunsPolicy = new ThreadPoolExecutor.CallerRunsPolicy();

        this.threadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, keepAliveTime, TimeUnit.MINUTES, blockingQueue, this, callerRunsPolicy);
        this.threadPool.allowCoreThreadTimeOut(true);
        this.threadPool.purge();

        this.truePassword = new AtomicReference<>();
    }

    @Override
    public Thread newThread(Runnable r) {
        String threadName = THREAD_POOL_NAME + COUNTER.getAndIncrement();
        Thread thread = new Thread(r, threadName);
        thread.setDaemon(true);

        return thread;
    }

    public boolean execute() {
        if (!this.shouldContinue()) {
            return false;
        }

        this.threadPool.execute(this);
        return this.shouldContinue();
    }

    public boolean shouldContinue() {
        return !this.passwords.isEmpty() && !this.httpUtil.getHasDispose().get() && !(this.threadPool.isShutdown() || this.threadPool.isTerminating() || this.threadPool.isTerminated());
    }

    public void waitForComplete() {
        log.info("任务已提交完毕, 等待执行完成, 请勿强制停止程序.");
        this.waitForComplete(false);
    }

    private void waitForComplete(boolean force) {
        if (force) {
            return;
        }

        while (true) {
            try {
                boolean noWorker = this.threadPool.getActiveCount() == 0 && this.threadPool.getQueue().isEmpty();
                if (noWorker || this.threadPool.awaitTermination(1, TimeUnit.SECONDS)) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void run() {
        try {
            String password;
            synchronized (this.passwords) {
                Optional<String> any = this.passwords.parallelStream().findAny();
                if (!any.isPresent()) {
                    this.shutdown(false);
                    return;
                }

                password = any.get();
                this.passwords.remove(password);
            }

            this.httpUtil.executeRequest(password);
        } catch (Exception e) {
            log.error("发生异常, 停止程序.", e);
            this.shutdown(true);
        }
    }

    public void shutdown(boolean force) {
        if (this.threadShutdown.get()) {
            return;
        }

        this.threadShutdown.set(true);
        this.threadPool.shutdownNow();
        log.info("正在等待线程任务执行结束, 请勿强制停止程序.");
        this.waitForComplete(force);

        Set<String> passwordsToWrite;
        if (this.httpUtil.getHasDispose().get()) {
            String truePassword = this.truePassword.get();

            passwordsToWrite = new HashSet<>();
            passwordsToWrite.add("https://pan.baidu.com/s/1" + this.httpUtil.getSUrl() + " 的提取码: " + truePassword);
        } else {
            passwordsToWrite = this.hasTestPasswords;
        }

        this.disposePasswordDictionary(passwordsToWrite);
        log.info("shutdown工作处理完成, 已测试的密码已经写入文件: {}.", this.passwordHasTestDictionary.getTargetFile().getAbsolutePath());
    }

    private void disposePasswordDictionary(Set<String> passwordsToSave) {
        try {
            this.passwordHasTestDictionary.appendPassword(passwordsToSave);
        } catch (IOException e) {
            log.error(String.format("密码写入文件失败, 文件: %s, 密码: %s.", this.passwordHasTestDictionary.getTargetFile().getAbsolutePath(), Arrays.toString(passwordsToSave.toArray(new String[0]))), e);
        }

        this.passwordHasTestDictionary.dispose();
    }

}
