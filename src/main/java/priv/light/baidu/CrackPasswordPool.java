package priv.light.baidu;

import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Light
 * @date 2022/3/30 21:37
 */

@Slf4j
@Data
public class CrackPasswordPool {

    private final AtomicBoolean threadShutdown;
    private final ThreadPoolExecutor threadPool;

    private final boolean validateShare;
    private final PasswordDictionary passwordHasTestDictionary;
    private final Set<String> passwords;
    private final Set<String> hasTestPasswords;
    private HttpUtil httpUtil;
    private PanUriValidator panUriValidator;
    private final AtomicReference<String> truePassword;
    private final int maxPoolSize;

    public CrackPasswordPool(boolean validateShare, int corePoolSize, @NonNull File passwordFile, @NonNull File hasTestPasswordFile, boolean distinct) throws IOException {
        this.threadShutdown = new AtomicBoolean();
        this.validateShare = validateShare;

        int count = 4;
        PasswordDictionary passwordDictionary = new PasswordDictionary(count, passwordFile);
        this.passwordHasTestDictionary = new PasswordDictionary(count, hasTestPasswordFile);

        Set<String> passwords;
        if (!passwordFile.exists() || passwordFile.length() == 0) {
            passwordDictionary.writeToFile(distinct);
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

        this.maxPoolSize = 2 * corePoolSize;
        this.threadPool = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.MINUTES, blockingQueue, new CrackThreadFactory(), callerRunsPolicy);
        this.threadPool.allowCoreThreadTimeOut(true);

        this.truePassword = new AtomicReference<>();
        PasswordTask.injectCrackPasswordPool(this);
    }

    public void setHttpUtil(HttpUtil httpUtil) {
        this.httpUtil = httpUtil;
        if (this.validateShare) {
            HttpGet panGet = new HttpGet(this.httpUtil.getPanUri());
            this.panUriValidator = new PanUriValidator(panGet);
        }
    }

    public boolean execute() {
        if (!this.shouldContinue()) {
            return false;
        }

        if (this.validateShare && this.panUriValidator.notFoundOrNoShareCheck()) {
            this.shutdown(true);
            return false;
        }

        this.threadPool.execute(new PasswordTask());
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

    public void shutdown(boolean force) {
        if (this.threadShutdown.get()) {
            return;
        }

        this.threadShutdown.set(true);
        if (force) {
            this.threadPool.shutdownNow();
        } else {
            this.threadPool.shutdown();
        }

        log.info("正在等待线程任务执行结束, 请勿强制停止程序.");
        this.waitForComplete(force);
        if(this.validateShare){
            this.panUriValidator.dispose();
        }

        Set<String> passwordsToWrite;
        if (this.httpUtil.getHasDispose().get()) {
            String truePassword = this.truePassword.get();

            passwordsToWrite = new HashSet<>();
            passwordsToWrite.add(this.httpUtil.getPanUri() + " 的提取码: " + truePassword);
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
