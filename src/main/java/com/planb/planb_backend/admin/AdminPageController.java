package com.planb.planb_backend.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * /admin 또는 /admin/ 접근 시 /admin/index.html로 리다이렉트
 * Spring Boot는 정적 리소스 디렉토리 인덱스를 자동 서빙하지 않으므로 명시적 처리 필요
 */
@Controller
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String adminRedirect() {
        return "redirect:/admin/index.html";
    }
}
