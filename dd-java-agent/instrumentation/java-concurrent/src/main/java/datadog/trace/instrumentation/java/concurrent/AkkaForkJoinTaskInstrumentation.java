package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.dispatch.forkjoin.ForkJoinPool;
import akka.dispatch.forkjoin.ForkJoinTask;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles Akka version.
 */
@Slf4j
@AutoService(Instrumenter.class)
public final class AkkaForkJoinTaskInstrumentation extends Instrumenter.Default {

  static final String TASK_CLASS_NAME = "akka.dispatch.forkjoin.ForkJoinTask";

  public AkkaForkJoinTaskInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface()).and(safeHasSuperType(named(TASK_CLASS_NAME)));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      AdviceUtils.class.getName(),
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put(Runnable.class.getName(), Span.class.getName());
    map.put(Callable.class.getName(), Span.class.getName());
    map.put(TASK_CLASS_NAME, Span.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("exec").and(takesArguments(0)).and(not(isAbstract())),
        ForkJoinTaskAdvice.class.getName());
    return transformers;
  }

  public static class ForkJoinTaskAdvice {

    /**
     * When {@link ForkJoinTask} object is submitted to {@link ForkJoinPool} as {@link Runnable} or
     * {@link Callable} it will not get wrapped, instead it will be casted to {@code ForkJoinTask}
     * directly. This means state is still stored in {@code Runnable} or {@code Callable} and we
     * need to use that state.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope enter(@Advice.This final ForkJoinTask thiz) {
      final ContextStore<ForkJoinTask, Span> contextStore =
          InstrumentationContext.get(ForkJoinTask.class, Span.class);
      Scope scope = AdviceUtils.startTaskScope(contextStore, thiz);
      if (thiz instanceof Runnable) {
        final ContextStore<Runnable, Span> runnableContextStore =
            InstrumentationContext.get(Runnable.class, Span.class);
        final Scope newScope = AdviceUtils.startTaskScope(runnableContextStore, (Runnable) thiz);
        if (null != newScope) {
          if (null != scope) {
            newScope.close();
          } else {
            scope = newScope;
          }
        }
      }
      if (thiz instanceof Callable) {
        final ContextStore<Callable, Span> callableContextStore =
            InstrumentationContext.get(Callable.class, Span.class);
        final Scope newScope = AdviceUtils.startTaskScope(callableContextStore, (Callable) thiz);
        if (null != newScope) {
          if (null != scope) {
            newScope.close();
          } else {
            scope = newScope;
          }
        }
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final Scope scope) {
      AdviceUtils.endTaskScope(scope);
    }
  }
}
