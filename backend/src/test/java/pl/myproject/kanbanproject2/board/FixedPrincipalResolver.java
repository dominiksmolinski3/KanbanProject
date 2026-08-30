package pl.myproject.kanbanproject2.board;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.myproject.kanbanproject2.user.User;

/**
 * Stands in for {@code @AuthenticationPrincipal}, which a standalone MockMvc setup does not wire.
 *
 * <p>Every board route now takes the caller, so every HTTP test needs one. Hoisting it out of
 * {@code UserControllerHttpTest}, where it started, keeps the six of them from each growing their
 * own copy.
 */
public final class FixedPrincipalResolver implements HandlerMethodArgumentResolver {

    private final User caller;

    public FixedPrincipalResolver(User caller) {
        this.caller = caller;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return User.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return caller;
    }
}
