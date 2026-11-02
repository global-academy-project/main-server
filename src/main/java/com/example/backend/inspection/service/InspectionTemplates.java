package com.example.backend.inspection.service;

import com.example.backend.inspection.entity.InspectionTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 점검표 양식 (yaml app.inspection.templates 의 JSON 파일). 기동 때 한 번 읽고 검사한다.
 * 점검표를 만들 때 항목(코드·질문)을 그 점검표에 복사하므로, 양식을 바꿔도 이미 만든 점검표는 그대로다.
 */
@Component
public class InspectionTemplates {

    public record Template(String code, String name, InspectionTarget targetType, List<Section> sections) {

        public List<Item> items() {
            return sections.stream().flatMap(s -> s.items().stream()).toList();
        }
    }

    public record Section(String title, List<Item> items) {
    }

    public record Item(String itemCode, String question, String detail) {
    }

    record TemplateFile(List<Template> templates) {
    }

    /** 항목 코드 → 소속 구역 제목·설명 (응답 표시용) */
    public record ItemInfo(String section, String detail) {
    }

    private final Map<String, Template> byCode = new LinkedHashMap<>();
    private final Map<String, Map<String, ItemInfo>> infos = new LinkedHashMap<>();

    public InspectionTemplates(@Value("${app.inspection.templates}") Resource resource, JsonMapper jsonMapper)
            throws IOException {
        TemplateFile file;
        try (InputStream in = resource.getInputStream()) {
            file = jsonMapper.readValue(in, TemplateFile.class);
        }
        if (file == null || file.templates() == null || file.templates().isEmpty()) {
            throw new IllegalStateException("점검표 양식이 없습니다: " + resource);
        }
        for (Template t : file.templates()) {
            check(t);
            if (byCode.put(t.code(), t) != null) {
                throw new IllegalStateException("점검표 양식 코드가 겹칩니다: " + t.code());
            }
            Map<String, ItemInfo> m = new LinkedHashMap<>();
            for (Section s : t.sections()) {
                for (Item i : s.items()) {
                    m.put(i.itemCode(), new ItemInfo(s.title(), i.detail()));
                }
            }
            infos.put(t.code(), m);
        }
    }

    public List<Template> all() {
        return List.copyOf(byCode.values());
    }

    public Optional<Template> find(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code.trim()));
    }

    public ItemInfo info(String templateCode, String itemCode) {
        return infos.getOrDefault(templateCode, Map.of()).get(itemCode);
    }

    /** DB 길이 제한과 같게 검사 (template_code 50, item_code 50, question 300) */
    private static void check(Template t) {
        if (t.code() == null || !t.code().matches("^[A-Z][A-Z0-9_]{0,49}$") || t.name() == null || t.name().isBlank()
                || t.targetType() == null || t.sections() == null || t.sections().isEmpty()) {
            throw new IllegalStateException("점검표 양식 형식이 잘못됐습니다: " + t.code());
        }
        Set<String> codes = new HashSet<>();
        for (Section s : t.sections()) {
            if (s.items() == null || s.items().isEmpty()) {
                throw new IllegalStateException("항목이 없는 구역이 있습니다: " + t.code() + " / " + s.title());
            }
            for (Item i : s.items()) {
                if (i.itemCode() == null || !i.itemCode().matches("^[A-Z][A-Z0-9_]{0,49}$")
                        || i.question() == null || i.question().isBlank() || i.question().length() > 300
                        || !codes.add(i.itemCode())) {
                    throw new IllegalStateException("점검 항목이 잘못됐거나 겹칩니다: " + t.code() + " / " + i.itemCode());
                }
            }
        }
    }
}
