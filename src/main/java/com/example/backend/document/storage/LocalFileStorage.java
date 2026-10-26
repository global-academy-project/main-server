package com.example.backend.document.storage;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/*
파일을 로컬 디스크에 저장한다. DB 에는 root 기준 상대경로만 기록한다.
원본 PDF : {userId}/{uuid}.pdf
추출 이미지: images/{documentId}/{runId}/{n}.png
*/
@Component
public class LocalFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.dir:./storage}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /* 업로드 PDF 저장 후 상대경로를 반환. 파일명은 항상 서버가 만든다. */
    public String store(MultipartFile file, Long userId) {
        String relative = userId + "/" + UUID.randomUUID() + ".pdf";
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("파일 저장 실패: {}", target, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일 저장에 실패했습니다.");
        }
        return relative;
    }

    /* 지정한 상대경로에 바이트를 저장한다 (추출 이미지용). */
    public void storeBytes(String relative, byte[] data) {
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            log.error("파일 저장 실패: {}", target, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일 저장에 실패했습니다.");
        }
    }

    /** 상대경로를 실제 경로로 변환. root 밖으로 벗어나는 경로는 거부한다. */
    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return p;
    }
    public boolean exists(String relative) {
        return Files.isRegularFile(resolve(relative));
    }

    /* 실패해도 예외를 던지지 않고 로그만 남긴다. */
    public void delete(String relative) {
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (IOException | RuntimeException e) {
            log.warn("파일 삭제 실패: {}", relative, e);
        }
    }

    /* 폴더와 그 안의 파일을 모두 삭제한다. 없으면 아무것도 하지 않는다. */
    public void deleteDirectory(String relative) {
        try {
            Path dir = resolve(relative);
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("삭제 실패: {}", p, e);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            log.warn("폴더 삭제 실패: {}", relative, e);
        }
    }
}