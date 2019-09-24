package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCUtils.connectionFromStatement;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.GlobalTracer;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.SpanInScope;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.Tracer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class PreparedStatementInstrumentation extends Instrumenter.Default {

  public PreparedStatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface()).and(safeHasSuperType(named("java.sql.PreparedStatement")));
  }

  @Override
  public String[] helperClassNames() {
    final List<String> helpers = new ArrayList<>(JDBCConnectionUrlParser.values().length + 9);

    helpers.add(packageName + ".DBInfo");
    helpers.add(packageName + ".DBInfo$Builder");
    helpers.add(packageName + ".JDBCUtils");
    helpers.add(packageName + ".JDBCMaps");
    helpers.add(packageName + ".JDBCConnectionUrlParser");

    helpers.add("datadog.trace.agent.decorator.BaseDecorator");
    helpers.add("datadog.trace.agent.decorator.ClientDecorator");
    helpers.add("datadog.trace.agent.decorator.DatabaseClientDecorator");
    helpers.add(packageName + ".JDBCDecorator");

    for (final JDBCConnectionUrlParser parser : JDBCConnectionUrlParser.values()) {
      helpers.add(parser.getClass().getName());
    }
    return helpers.toArray(new String[0]);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic()),
        PreparedStatementAdvice.class.getName());
  }

  public static class PreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SpanInScope startSpan(@Advice.This final PreparedStatement statement) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(PreparedStatement.class);
      if (callDepth > 0) {
        return null;
      }

      final Connection connection = connectionFromStatement(statement);
      if (connection == null) {
        return null;
      }
      Tracer tracer = GlobalTracer.get();
      final Span span =
          tracer.spanBuilder("database.query").setSpanKind(Span.Kind.CLIENT).startSpan();
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, connection);
      DECORATE.onPreparedStatement(span, statement);
      span.setAttribute("span.origin.type", statement.getClass().getName());
      return SpanInScope.create(span, tracer.withSpan(span));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final SpanInScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope != null) {
        Span span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        scope.close();
        span.end();
        CallDepthThreadLocalMap.reset(PreparedStatement.class);
      }
    }
  }
}
