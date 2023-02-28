package cn.lili.common.security.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
public class ContextTrackFilter extends OncePerRequestFilter {

    private final String MY_SHOP_TRACKING_ID = "trackingId";

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain) throws ServletException, IOException {
        try {
            String trackingId = getOrGenerateTrackingId(httpServletRequest);
            httpServletResponse.setHeader(MY_SHOP_TRACKING_ID, trackingId);
            filterChain.doFilter(httpServletRequest, httpServletResponse);
        } finally {
            MDC.clear();
        }
    }


    private String getOrGenerateTrackingId(HttpServletRequest httpServletRequest) {
        String trackingId = httpServletRequest.getHeader(MY_SHOP_TRACKING_ID);
        if (!StringUtils.hasText(trackingId)) {
            trackingId = UUID.randomUUID().toString();
            MDC.put(MY_SHOP_TRACKING_ID, trackingId);
        } else {
            MDC.put(MY_SHOP_TRACKING_ID, trackingId);
        }
        return trackingId;
    }
}
