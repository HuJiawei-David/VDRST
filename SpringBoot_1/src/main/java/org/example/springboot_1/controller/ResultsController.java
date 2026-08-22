package org.example.springboot_1.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.springboot_1.common.Result;
import org.example.springboot_1.entity.VirusMatch;
import org.example.springboot_1.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.example.springboot_1.Generator.FileGenerator;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/results")
public class ResultsController {

    @Value("${file.download.dir}")
    private String downloadDir;

    @Autowired
    private FileGenerator fileGenerator;

    @PostMapping("/download")
    public ResponseEntity<FileSystemResource> downloadResult(
            @RequestBody Map<String, Object> requestBody,
            @RequestHeader(value = "token", required = false) String token) {

        try {
            // 验证token
            if (token == null || !validateToken(token)) {
                throw new ServiceException("401", "Unauthorized");
            }

            String format = (String) requestBody.get("format");
            Object resultObj = requestBody.get("result");

            if (format == null || (!"pdf".equalsIgnoreCase(format) && !"word".equalsIgnoreCase(format))) {
                throw new ServiceException("400", "Invalid format. Choose 'pdf' or 'word'.");
            }

            if (resultObj == null) {
                throw new ServiceException("400", "Result data is missing.");
            }

            VirusMatch result = new ObjectMapper().convertValue(resultObj, VirusMatch.class);
            String content = generateResultContent(result);
            String filePath = "pdf".equalsIgnoreCase(format) ?
                    fileGenerator.generatePDF(content) :
                    fileGenerator.generateWord(content);

            if (filePath == null || !new File(filePath).exists()) {
                throw new ServiceException("404", "Generated file not found.");
            }

            // 准备下载响应
            File file = new File(filePath);
            FileSystemResource resource = new FileSystemResource(file);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "pdf".equalsIgnoreCase(format) ?
                    MediaType.APPLICATION_PDF_VALUE :
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.getName());
            headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()));

            return new ResponseEntity<>(resource, headers, HttpStatus.OK);

        } catch (ServiceException e) {
            return handleServiceException(e);
        } catch (Exception e) {
            return handleGlobalException(e);
        }
    }

    private ResponseEntity<FileSystemResource> handleServiceException(ServiceException e) {
        return ResponseEntity.status(HttpStatus.valueOf(Integer.parseInt(e.getCode())))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private ResponseEntity<FileSystemResource> handleGlobalException(Exception e) {
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private boolean validateToken(String token) {
        return true; // 模拟token验证逻辑
    }

    private String generateResultContent(VirusMatch result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Matched Sequence ID: ").append(result.getMatchedSequence()).append("\n");
        sb.append("Similarity Score: ").append(result.getSimilarityScore()).append("%\n");
        sb.append("Description:\n").append(result.getJobTitle()).append("\n");
        return sb.toString();
    }
}
