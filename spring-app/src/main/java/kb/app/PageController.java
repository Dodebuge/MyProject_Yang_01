package kb.app;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 화면 주소. HTML은 빌드할 때 저장소 루트에서 static/으로 복사됩니다 (pom.xml). */
@Controller
class PageController {

    @GetMapping("/")
    String home() {
        return "forward:/home.html";
    }

    @GetMapping("/heatmap")
    String heatmap() {
        return "forward:/heatmap.html";
    }

    @GetMapping("/me")
    String me() {
        return "forward:/me.html";
    }
}
