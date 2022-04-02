package priv.light.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NonNull;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author Light
 * @date 2022/3/30 23:16
 */
@Data
public class ResponseHandler implements HttpClientResponseHandler<ResponseResult> {

    private static final int NOT_FOUND = 404;
    private static final int PROXY_AUTH = 407;
    private static final String JSON = "application/json";
    private static final int PASSWORD_ERROR = -9;

    private final ResponseResult result;

    public ResponseHandler(@NonNull String password, HttpHost proxy) {
        this.result = new ResponseResult(password, proxy);
    }

    @Override
    public ResponseResult handleResponse(ClassicHttpResponse response) {
        try (final ClassicHttpResponse closeableResponse = response) {
            int code = closeableResponse.getCode();
            if (code != NOT_FOUND && code != PROXY_AUTH) {
                HttpEntity body = closeableResponse.getEntity();
                if (!body.getContentType().contains(JSON)) {
                    this.result.setTestPasswordResult(TestPasswordResult.FOUND_ERROR);
                    return this.result;
                }

                try {
                    String bodyText = EntityUtils.toString(body, StandardCharsets.UTF_8);
                    EntityUtils.consume(body);

                    JsonNode responseJson = HttpUtil.OBJECT_MAPPER.readTree(bodyText);
                    int errorNo = responseJson.path("errno").asInt();
                    if (errorNo == 0) {
                        this.result.setTestPasswordResult(TestPasswordResult.SUCCESS_FOUND);
                        return this.result;
                    }

                    if (errorNo == PASSWORD_ERROR) {
                        this.result.setTestPasswordResult(TestPasswordResult.PASSWORD_ERROR);
                        return this.result;
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.result.setTestPasswordResult(TestPasswordResult.NEED_CHANGE_PROXY);
        return this.result;
    }

}
