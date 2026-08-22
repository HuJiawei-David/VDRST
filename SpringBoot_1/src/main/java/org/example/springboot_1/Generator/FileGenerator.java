// FileGenerator.java
package org.example.springboot_1.Generator;

import com.itextpdf.text.Document;
import com.itextpdf.text.pdf.PdfWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;

@Service
public class FileGenerator {

    @Value("${file.download.dir}")
    private String downloadDir; // 需要在 application.yml 中配置

    public String generatePDF(String content) {
        String filePath = downloadDir + "/" + System.currentTimeMillis() + ".pdf";
        try {
            Document document = new Document();
            PdfWriter.getInstance(document, new FileOutputStream(filePath));
            document.open();
            document.add(new com.itextpdf.text.Paragraph(content));
            document.close();
            return filePath;
        } catch (Exception e) {
            System.err.println("Failed to generate PDF: " + e.getMessage());
            return null;
        }
    }

    public String generateWord(String content) {
        String filePath = downloadDir + "/" + System.currentTimeMillis() + ".docx";
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            paragraph.createRun().setText(content);
            try (FileOutputStream out = new FileOutputStream(filePath)) {
                doc.write(out);
            }
            return filePath;
        } catch (IOException e) {
            System.err.println("Failed to generate Word document: " + e.getMessage());
            return null;
        }
    }
}
