package priv.light.baidu;

import lombok.Data;
import lombok.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Light
 * @date 2022/3/30 21:37
 */

@Data
public class CrackPasswordPool implements ThreadFactory, Runnable {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final String THREAD_POOL_NAME = "MyPool Thread Pool-Thread-";
    public static final int CORE_POOL_SIZE;
    public static final int MAX_POOL_SIZE;

    static {
        CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() + 1;
        MAX_POOL_SIZE = 2 * CORE_POOL_SIZE;
    }

    private final ThreadPoolExecutor threadPool;

    private final PasswordDictionary passwordHasTestDictionary;
    private final Set<String> passwords;
    private final Set<String> hasTestPasswords;
    private HttpUtil httpUtil;
    private final AtomicReference<String> truePassword;

    public CrackPasswordPool(@NonNull File passwordFile, @NonNull File hasTestPasswordFile) throws IOException {
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

        int keepAliveTime = 2;

        BlockingQueue<Runnable> blockingQueue = new LinkedBlockingQueue<>(Integer.MAX_VALUE / 100);
        ThreadPoolExecutor.CallerRunsPolicy callerRunsPolicy = new ThreadPoolExecutor.CallerRunsPolicy();

        this.threadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, keepAliveTime, TimeUnit.MINUTES, blockingQueue, callerRunsPolicy);
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

    private void waitForComplete() {
        while (true) {
            try {
                if (this.threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void run() {
        String password;
        synchronized (this.passwords) {
            Optional<String> any = this.passwords.parallelStream().findAny();
            if (!any.isPresent()) {
                return;
            }

            password = any.get();
            this.passwords.remove(password);
        }

        this.httpUtil.executeRequest(password);
    }

    public void shutdown() {
        this.threadPool.shutdownNow();
        this.waitForComplete();

        Set<String> passwordsToWrite;
        if (this.httpUtil.getHasDispose().get()) {
            String truePassword = this.truePassword.get();

            passwordsToWrite = new HashSet<>();
            passwordsToWrite.add("https://pan.baidu.com/s/1" + this.httpUtil.getSUrl() + " 的提取码: " + truePassword);
        } else {
            passwordsToWrite = this.hasTestPasswords;
        }

        this.disposePasswordDictionary(passwordsToWrite);
    }

    private void disposePasswordDictionary(Set<String> passwordsToSave) {
        try {
            this.passwordHasTestDictionary.appendPassword(passwordsToSave);
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.passwordHasTestDictionary.dispose();
    }

}
