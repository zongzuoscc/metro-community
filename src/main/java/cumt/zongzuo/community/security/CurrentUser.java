package cumt.zongzuo.community.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Long userId = idOrNull();
        if (userId == null) {
            throw new IllegalStateException("未认证的用户请求");
        }
        return userId;
    }

    /**
     * Returns the authenticated account id when the request is authenticated,
     * otherwise {@code null}. It is intended for public endpoints that can
     * optionally personalize their response.
     */
    public static Long idOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof Long userId)) {
            return null;
        }
        return userId;
    }
}
