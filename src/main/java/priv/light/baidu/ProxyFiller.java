package priv.light.baidu;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.RequestFailedException;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * @author Light
 * @date 2022/4/2 14:29
 */

@Slf4j
public class ProxyFiller implements ExecChainHandler {

    private final HttpUtil httpUtil;
    private final HttpRequestRetryStrategy retryStrategy;

    public ProxyFiller(@NonNull HttpUtil httpUtil, @NonNull RetryStrategy retryStrategy) {
        this.httpUtil = httpUtil;
        this.retryStrategy = retryStrategy;
    }

    @Override
    public ClassicHttpResponse execute(
            @NonNull final ClassicHttpRequest request,
            @NonNull ExecChain.Scope scope,
            final ExecChain chain) throws IOException, HttpException {
        ClassicHttpRequest currentRequest = request;

        for (int execCount = 1; ; execCount++) {
            final ClassicHttpResponse response;
            try {
                response = chain.proceed(currentRequest, scope);
            } catch (final IOException ex) {
                Object[] proxyResult = this.ioExceptionRetry(execCount, request, scope, ex);
                currentRequest = (ClassicHttpRequest) proxyResult[0];
                scope = (ExecChain.Scope) proxyResult[1];
                continue;
            }

            try {
                if (!this.responseReTry(execCount, request, scope, response)) {
                    return response;
                }

                Object[] proxyResult = this.responseNoRetry(execCount, scope, response);
                currentRequest = (ClassicHttpRequest) proxyResult[0];
                scope = (ExecChain.Scope) proxyResult[1];
            } catch (final RuntimeException ex) {
                response.close();
                throw ex;
            }
        }
    }

    private Object[] ioExceptionRetry(int execCount, ClassicHttpRequest request, ExecChain.Scope scope, IOException ex) throws IOException {
        if (scope.execRuntime.isExecutionAborted()) {
            throw new RequestFailedException("Request aborted");
        }

        String exchangeId = scope.exchangeId;
        final HttpEntity requestEntity = request.getEntity();
        if (requestEntity != null && !requestEntity.isRepeatable()) {
            if (log.isDebugEnabled()) {
                log.debug("{} cannot retry non-repeatable request", exchangeId);
            }
            throw ex;
        }

        HttpClientContext context = scope.clientContext;
        HttpRoute route = scope.route;
        if (this.retryStrategy.retryRequest(request, ex, execCount, context)) {
            if (log.isDebugEnabled()) {
                log.debug("{} {}", exchangeId, ex.getMessage(), ex);
            }

            if (log.isInfoEnabled()) {
                log.info("Recoverable I/O exception ({}) caught when processing request to {}",
                        ex.getClass().getName(), route);
            }

            ExecChain.Scope newScope = this.setProxy(scope);
            return new Object[]{ClassicRequestBuilder.copy(newScope.originalRequest).build(), newScope};
        } else {
            if (ex instanceof NoHttpResponseException) {
                final NoHttpResponseException updatedEx = new NoHttpResponseException(
                        route.getTargetHost().toHostString() + " failed to respond");
                updatedEx.setStackTrace(ex.getStackTrace());
                updatedEx.printStackTrace();

                ExecChain.Scope newScope = this.setProxy(scope);
                return new Object[]{ClassicRequestBuilder.copy(newScope.originalRequest).build(), newScope};
            }
            throw ex;
        }
    }

    private boolean responseReTry(int execCount, ClassicHttpRequest request, ExecChain.Scope scope, ClassicHttpResponse response) {
        final HttpEntity entity = request.getEntity();
        String exchangeId = scope.exchangeId;
        if (entity != null && !entity.isRepeatable()) {
            if (log.isDebugEnabled()) {
                log.debug("{} cannot retry non-repeatable request", exchangeId);
            }
            return false;
        }

        HttpClientContext context = scope.clientContext;
        if (this.retryStrategy.retryRequest(response, execCount, context)) {
            TimeValue nextInterval = this.retryStrategy.getRetryInterval(response, execCount, context);
            if (TimeValue.isPositive(nextInterval)) {
                final RequestConfig requestConfig = context.getRequestConfig();
                final Timeout responseTimeout = requestConfig.getResponseTimeout();
                return responseTimeout == null || nextInterval.compareTo(responseTimeout) <= 0;
            }
        }

        return false;
    }

    private Object[] responseNoRetry(int execCount, ExecChain.Scope scope, ClassicHttpResponse response) throws IOException {
        String exchangeId = scope.exchangeId;
        HttpClientContext context = scope.clientContext;
        TimeValue nextInterval = this.retryStrategy.getRetryInterval(response, execCount, context);
        response.close();
        if (TimeValue.isPositive(nextInterval)) {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("{} wait for {}", exchangeId, nextInterval);
                }
                nextInterval.sleep();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        ExecChain.Scope newScope = this.setProxy(scope);
        return new Object[]{ClassicRequestBuilder.copy(newScope.originalRequest).build(), newScope};
    }

    private ExecChain.Scope setProxy(ExecChain.Scope scope) {
        HttpRoute route = scope.route;
        HttpHost proxy = route.getProxyHost();
        this.httpUtil.configProxy(proxy);

        route = new HttpRoute(route.getTargetHost(), route.getLocalAddress(), this.httpUtil.getHttpProxy().getProxy(), route.isSecure(), route.getTunnelType(), route.getLayerType());
        return new ExecChain.Scope(scope.exchangeId, route, scope.originalRequest, scope.execRuntime, scope.clientContext);
    }

}
