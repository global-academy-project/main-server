package com.example.backend.attachment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 첨부 파일 저장소 (계획서 11장 파일 저장 위치). 루트는 yaml app.storage.dir 한 곳.
 * 경로: attachments/{현장id}/{yyyy}/{MM}/{UUID}.{확장자}. 파일 이름은 항상 서버가 만들고, DB 에는 루트 기준 상대 경로만 둔다.
 * 루트 밖을 가리키는 경로는 읽지도 지우지도 않는다.
 */
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(@Value("${app.storage.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** 저장하고 루트 기준 상대 경로("/" 구분)를 돌려준다. 같은 폴더에 임시 파일로 쓴 뒤 옮긴다 */
    public String store(Long siteId, LocalDate date, String extension, byte[] bytes) {
        String relative = "attachments/%d/%04d/%02d/%s.%s".formatted(siteId, date.getYear(), date.getMonthValue(),
                UUID.randomUUID(), extension);
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 파일을 저장하지 못했습니다.", e);
        }
        return relative;
    }

    /** 읽을 파일 경로. 없으면 null */
    public Path existing(String relative) {
        Path p = resolve(relative);
        return Files.isRegularFile(p) ? p : null;
    }

    /** 지우기 (없으면 그냥 넘어감) */
    public void delete(String relative) {
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 파일을 지우지 못했습니다.", e);
        }
    }

    Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new IllegalArgumentException("저장소 밖 경로입니다.");
        }
        return p;
    }
}
