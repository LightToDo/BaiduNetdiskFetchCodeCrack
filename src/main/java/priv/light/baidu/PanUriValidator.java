package priv.light.baidu;

import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.io.CloseMode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author Light
 * @date 2022/4/7 10:44
 */

@Slf4j
@Data
public class PanUriValidator {

    private static final String NO_SHARE_ELEMENT_ID = "share_nofound_des";
    private static final String HTML = "html";

    private static final CloseableHttpClient HTTP_CLIENT;

    static {
        PoolingHttpClientConnectionManager proxyConnectionManager = new PoolingHttpClientConnectionManager();
        HTTP_CLIENT = HttpClients.custom().disableAutomaticRetries().setConnectionManager(proxyConnectionManager).evictExpiredConnections().build();
    }

    private final HttpGet panHttpGet;

    public PanUriValidator(@NonNull HttpGet panHttpGet) {
        this.panHttpGet = panHttpGet;
    }

    public boolean notFoundOrNoShareCheck() {
        boolean notFoundOrNoShare;

        try {
            CloseableHttpResponse response = HTTP_CLIENT.execute(this.panHttpGet);
            int code = response.getCode();
            notFoundOrNoShare = code == HttpStatus.SC_NOT_FOUND;
            if (!notFoundOrNoShare) {
                HttpEntity entity = response.getEntity();
                if(!entity.getContentType().contains(HTML)){
                    EntityUtils.consume(entity);
                    return false;
                }

                String html = EntityUtils.toString(entity, StandardCharsets.UTF_8.name());
                EntityUtils.consume(entity);

                Document htmlDocument = Jsoup.parse(html);
                Element body = htmlDocument.body();
                Element noShareElement = body.getElementById(NO_SHARE_ELEMENT_ID);
                return noShareElement != null;
            }
        } catch (IOException e) {
            log.error("请求出现错误.", e);
        } catch (ParseException e) {
            log.error("Entity转换异常, 请处理.", e);
        }

        log.error(String.format("百度网盘分享链接无效: %s.", this.panHttpGet));
        return true;
    }

    public void dispose() {
        HTTP_CLIENT.close(CloseMode.IMMEDIATE);
    }

}
