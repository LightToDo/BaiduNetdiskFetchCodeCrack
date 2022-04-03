package priv.light.baidu;

import org.apache.hc.client5.http.classic.methods.HttpGet;

import java.io.File;
import java.io.IOException;

/**
 * @author Light
 * @date 2022/3/25 21:21
 */

public class BaiDuPanCrack {

    public static void main(String[] args) throws IOException {
        File passwordFile = new File("password.txt");
        File hasTestPasswordFile = new File("passwordHasTest.txt");
        //String sUrl = "T9_CraGw7k8qcZQuOHk7fg";
        String sUrl = "lXK8QmWds5EmkabUvKGTjw";

        CrackPasswordPool pool = new CrackPasswordPool(passwordFile, hasTestPasswordFile);

        HttpGet proxy = new HttpGet("http://api.xiequ.cn/VAD/GetIp.aspx?act=getturn51&uid=56669&vkey=CD49B0BABC5D0692AF75B0A3FC47CDAD&num=1&time=6&plat=0&re=0&type=7&so=1&group=51&ow=1&spl=1&addr=&db=1");
        HttpUtil httpUtil = new HttpUtil(sUrl, proxy, pool);

        HttpGet clearWhiteList = new HttpGet("http://op.xiequ.cn/IpWhiteList.aspx?uid=56669&ukey=524F8242625394E2851494D7D68D5A84&act=del&ip=all");
        StringBuilder addWhiteList = new StringBuilder("http://op.xiequ.cn/IpWhiteList.aspx?uid=56669&ukey=524F8242625394E2851494D7D68D5A84&act=add&ip=");
        HttpGet ip = new HttpGet("http://api.xiequ.cn/VAD/OnlyIp.aspx?yyy=123");

        Runtime.getRuntime().addShutdownHook(new ManualExitShutDownHook(pool));

        ProxyResponseHandler proxyResponseHandler = httpUtil.getProxyResponseHandler();
        proxyResponseHandler.setAddWhiteList(addWhiteList);
        proxyResponseHandler.setClearWhiteList(clearWhiteList);
        proxyResponseHandler.setIp(ip);

        pool.setHttpUtil(httpUtil);

        while (true) {
            if (!pool.execute()) {
                break;
            }
        }

        pool.waitForComplete();
    }

}
