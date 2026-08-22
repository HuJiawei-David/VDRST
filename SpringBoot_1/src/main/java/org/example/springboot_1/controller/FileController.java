package org.example.springboot_1.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/file")
public class FileController {

    @Value("${file.download.dir}")
    private String downloadDir;

    @GetMapping("/download/{fileName}")
    public ResponseEntity<FileSystemResource> downloadFile(
            @PathVariable String fileName,
            @RequestHeader(value = "token", required = false) String token) {
        try {
            // 验证 Token（示例代码，实际逻辑请根据业务需求实现）
            if (token == null || !validateToken(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
            }

            // 检查文件是否存在
            File file = new File(downloadDir + "/" + fileName);
            if (!file.exists()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            FileSystemResource resource = new FileSystemResource(file);
            String mimeType = "application/octet-stream";
            if (fileName.endsWith(".pdf")) {
                mimeType = "application/pdf";
            } else if (fileName.endsWith(".docx")) {
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mimeType));
            headers.setContentDisposition(ContentDisposition.builder("attachment").filename(fileName).build());

            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private boolean validateToken(String token) {
        // 实现令牌验证逻辑
        return true; // 示例：始终返回 true
    }
}
