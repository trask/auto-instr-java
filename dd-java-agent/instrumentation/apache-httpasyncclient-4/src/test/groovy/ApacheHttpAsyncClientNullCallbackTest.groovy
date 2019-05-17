import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.message.BasicHeader
import org.junit.Ignore
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.Future

/**
 * TODO: we would like not to ugnore this test, but currently this test is flaky
 * The problem is that http-request span is closed asynchronously and when we provide no callback (like 39)
 * we cannot synchronise on when it is closed. Possible soltion here would be to rewrite this to not run
 * tests will callbacks somehow because they make no sense in 'fire-and-forget' scenarios.
 */
@Ignore
class ApacheHttpAsyncClientNullCallbackTest extends HttpClientTest<ApacheHttpAsyncClientDecorator> {

  @AutoCleanup
  @Shared
  def client = HttpAsyncClients.createDefault()

  def setupSpec() {
    client.start()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
    assert method == "GET"

    HttpGet request = new HttpGet(uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    // The point here is to test case when callback is null - fire-and-forget style
    // So to make sure request is done we start request, wait for future to finish
    // and then call callback if present.
    Future future = client.execute(request, null)
    future.get()
    if (callback != null) {
      callback()
    }
    return 200
  }

  @Override
  ApacheHttpAsyncClientDecorator decorator() {
    return ApacheHttpAsyncClientDecorator.DECORATE
  }

  @Override
  Integer statusOnRedirectError() {
    return 302
  }
}
