package bank.tessera.backoffice;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import javax.servlet.ServletContext;

/**
 * A {@link ServletContext} that answers {@code getInitParameter} and nothing else.
 *
 * <p>{@code ServletContext} declares some fifty methods and this module needs one of them. Writing
 * the other forty-nine as stubs would be pages of noise that no test reads, so a dynamic proxy
 * answers the one that matters and refuses the rest by name - which turns "the test quietly got a
 * null from a method nobody meant to call" into a failure that says which method it was.
 *
 * <p>No mocking framework, deliberately. This tier is JUnit 4 and the JDK, as a 2011 team's test
 * scaffolding was before Mockito was in every POM.
 */
final class StubServletContext {

    private StubServletContext() {
    }

    static ServletContext withInitParameters(final Map<String, String> parameters) {
        return (ServletContext) Proxy.newProxyInstance(
                StubServletContext.class.getClassLoader(),
                new Class<?>[] {ServletContext.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        if ("getInitParameter".equals(method.getName())) {
                            return parameters.get(arguments[0]);
                        }
                        if ("toString".equals(method.getName())) {
                            return "StubServletContext" + parameters;
                        }
                        throw new UnsupportedOperationException(
                                "this stub answers getInitParameter only; something called "
                                        + method.getName());
                    }
                });
    }
}
