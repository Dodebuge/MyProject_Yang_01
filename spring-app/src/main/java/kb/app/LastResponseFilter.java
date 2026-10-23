package kb.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 마지막 API 응답 (화면 첫 로딩용). 컨트롤러 앞에서 처리합니다.
 *   ?cached=1 : last_api/*.txt의 마지막으로 성공한 응답을 KB 조회 없이 바로 돌려줌 (없으면 404)
 *   그 밖     : 컨트롤러 응답이 200이면 last_api/*.txt에 저장
 */
@Component
class LastResponseFilter extends OncePerRequestFilter {

    private final LastResponse last;

    LastResponseFilter(KbProperties props) {
        this.last = new LastResponse(props.data("last_api"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        Path file = last.file(req.getRequestURI().substring(req.getContextPath().length()), req.getParameterMap());
        if (file == null) {
            chain.doFilter(req, resp);
            return;
        }
        if (LastResponse.cached(req.getParameterMap())) {
            String saved = LastResponse.read(file);
            byte[] body = (saved != null ? saved : "{\"error\":\"저장된 응답이 없습니다\"}").getBytes(StandardCharsets.UTF_8);
            resp.setStatus(saved != null ? 200 : 404);
            resp.setContentType("application/json; charset=utf-8");
            resp.setHeader("Cache-Control", "no-store");
            resp.setContentLength(body.length);
            resp.getOutputStream().write(body);
            return;
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(resp);
        chain.doFilter(req, wrapped);
        if (wrapped.getStatus() == 200) {
            try {
                LastResponse.save(file, new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
            } catch (IOException e) {  // 첫 로딩용 저장이 안 돼도 응답은 그대로 보냄 (예전엔 여기서 예외가 나 응답 본문이 사라졌음)
                logger.warn("마지막 응답 저장 실패 (" + file + "): " + e);
            }
        }
        wrapped.copyBodyToResponse();
    }
}
