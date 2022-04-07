package priv.light.baidu;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * @author Light
 * @date 2022/4/7 11:55
 */

@Slf4j
public class PasswordTask implements Runnable {

    private static CrackPasswordPool crackPasswordPool;

    public static void injectCrackPasswordPool(CrackPasswordPool crackPasswordPool) {
        if (PasswordTask.crackPasswordPool != null) {
            return;
        }

        synchronized (PasswordTask.class) {
            PasswordTask.crackPasswordPool = crackPasswordPool;
        }
    }

    @Override
    public void run() {
        try {
            String password;
            synchronized (crackPasswordPool.getPasswords()) {
                Optional<String> any = crackPasswordPool.getPasswords().parallelStream().findAny();
                if (!any.isPresent()) {
                    return;
                }

                password = any.get();
                crackPasswordPool.getPasswords().remove(password);
            }

            crackPasswordPool.getHttpUtil().executeRequest(password);
        } catch (Exception e) {
            log.error("发生待处理异常.", e);
        }
    }
}
