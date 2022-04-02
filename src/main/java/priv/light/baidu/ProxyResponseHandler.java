package priv.light.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author Light
 * @date 2022/3/31 12:06
 */

@Data
public class ProxyResponseHandler implements HttpClientResponseHandler<HttpHost> {

    private static final String SUCCESS = "success";

    private CloseableHttpClient proxyClient;
    private StringBuilder addWhiteList;
    private HttpGet clearWhiteList;
    private HttpGet ip;

    @Override
    public HttpHost handleResponse(ClassicHttpResponse response) throws IOException, ParseException {
        if (this.proxyClient == null || this.addWhiteList == null || this.clearWhiteList == null || this.ip == null) {
            throw new NoHttpResponseException("HttpProxyClient or WhiteList or Ip HttpGet is null.");
        }

        String bodyText = this.entity2String(response);
        JsonNode ipAndPort = HttpUtil.OBJECT_MAPPER.readTree(bodyText);
        JsonNode data = ipAndPort.path("data").path(0);
        String ip = data.path("IP").asText();
        int port = data.path("Port").asInt();
        if (ip.isEmpty()) {
            CloseableHttpResponse ipResponse = this.proxyClient.execute(this.ip);
            if (ipResponse.getCode() != HttpStatus.SC_SUCCESS) {
                return null;
            }

            String localIp = this.entity2String(ipResponse);
            HttpGet addGet = new HttpGet(this.addWhiteList.append(localIp).toString());
            do {
                ipResponse = this.proxyClient.execute(this.clearWhiteList);
                bodyText = this.entity2String(ipResponse);
            } while (!SUCCESS.equals(bodyText));

            do {
                ipResponse = this.proxyClient.execute(addGet);
                bodyText = this.entity2String(ipResponse);
            } while (!SUCCESS.equals(bodyText));

            return null;
        }

        return new HttpHost(ip, port);
    }

    private String entity2String(ClassicHttpResponse response) throws IOException, ParseException {
        HttpEntity entity = response.getEntity();
        String bodyText = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        EntityUtils.consume(entity);

        return bodyText;
    }

}
