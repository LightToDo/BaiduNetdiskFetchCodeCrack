package priv.light.baidu;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpHost;

/**
 * @author Light
 * @date 2022/3/31 15:00
 */

@Data
@RequiredArgsConstructor
public class ResponseResult {

    private final String password;
    private final HttpHost proxy;
    private TestPasswordResult testPasswordResult;

}
