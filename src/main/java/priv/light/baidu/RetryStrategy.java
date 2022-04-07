package priv.light.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author Light
 * @date 2022/4/2 12:32
 */

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class RetryStrategy extends DefaultHttpRequestRetryStrategy {

    private static final String JSON = "application/json";
    private static final int PASSWORD_ERROR = -9;

    private final CrackPasswordPool crackPasswordPool;

    public RetryStrategy(@NonNull CrackPasswordPool crackPasswordPool) {
        this(TimeValue.ofNanoseconds(5000), crackPasswordPool);
    }

    public RetryStrategy(TimeValue defaultRetryInterval, @NonNull CrackPasswordPool crackPasswordPool) {
        super(Integer.MAX_VALUE, defaultRetryInterval);
        this.crackPasswordPool = crackPasswordPool;
    }

    @Override
    public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
        if (!(response instanceof ClassicHttpResponse)) {
            return false;
        }

        HttpUtil httpUtil = this.crackPasswordPool.getHttpUtil();
        boolean needRetry = true;
        try {
            ClassicHttpRequest request = (ClassicHttpRequest) context.getAttribute("http.request");
            List<NameValuePair> parameters = EntityUtils.parse(request.getEntity());
            String password = parameters.get(0).getValue();

            int code = response.getCode();
            if (code != HttpStatus.SC_NOT_FOUND && code != HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED) {
                HttpEntity body = ((ClassicHttpResponse) response).getEntity();
                if (body.getContentType().contains(JSON)) {
                    String bodyText = EntityUtils.toString(body, StandardCharsets.UTF_8);
                    EntityUtils.consume(body);

                    JsonNode responseJson = HttpUtil.OBJECT_MAPPER.readTree(bodyText);
                    int errorNo = responseJson.path("errno").asInt();

                    if (errorNo == 0) {
                        this.crackPasswordPool.getTruePassword().set(password);
                        httpUtil.dispose();
                        needRetry = false;
                    } else {
                        if (errorNo == PASSWORD_ERROR) {
                            log.info("提取码错误: {}", password);
                            this.crackPasswordPool.getHasTestPasswords().add(password);
                            needRetry = false;
                        }
                    }
                }
            }
        } catch (ParseException e) {
            log.error("Entity 转换异常.", e);
        } catch (IOException e) {
            log.error("JSON 转换异常.", e);
        }

        return needRetry;
    }

}
