package com.auracademic.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

@Service
public class DocumentExtractorService {

    public Map<String, Object> extractContent(byte[] fileBytes, String filename) throws IOException {
        Map<String, Object> content = new HashMap<>();
        List<String> images = new ArrayList<>();
        String text;

        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            text = extractFromPdf(new ByteArrayInputStream(fileBytes), images);
        } else if (filename != null && filename.toLowerCase().endsWith(".docx")) {
            text = extractFromDocx(new ByteArrayInputStream(fileBytes), images);
        } else if (filename != null && filename.toLowerCase().endsWith(".txt")) {
            text = new String(fileBytes, StandardCharsets.UTF_8);
        } else {
            throw new IOException("Định dạng file không được hỗ trợ.");
        }

        content.put("text", cleanText(text));
        content.put("images", images);
        return content;
    }

    private String extractFromDocx(InputStream is, List<String> images) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            int imgCount = 0;

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    sb.append(paragraph.getText());
                    // Kiểm tra xem paragraph có chứa ảnh không
                    for (XWPFRun run : paragraph.getRuns()) {
                        List<XWPFPicture> pictures = run.getEmbeddedPictures();
                        for (XWPFPicture pic : pictures) {
                            if (imgCount < 10) {
                                String base64 = resizeAndBase64(pic.getPictureData().getData());
                                images.add(base64);
                                sb.append("\n[IMG_").append(imgCount).append("]\n");
                                imgCount++;
                            }
                        }
                    }
                    sb.append("\n");
                } else if (element instanceof XWPFTable table) {
                    sb.append(tableToMarkdown(table));
                }
            }
            return sb.toString();
        }
    }

    private String extractFromPdf(InputStream is, List<String> images) throws IOException {
        byte[] bytes = is.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int maxPages = Math.min(doc.getNumberOfPages(), 10);
            for (int i = 0; i < maxPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "jpeg", baos);
                images.add(Base64.getEncoder().encodeToString(baos.toByteArray()));
            }
            // Trả về chuỗi rỗng để báo hiệu rằng nội dung PDF nằm hoàn toàn trong biến images
            return ""; 
        }
    }

    private String tableToMarkdown(XWPFTable table) {
        StringBuilder sb = new StringBuilder("\n");
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            sb.append("| ");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(cell.getText().replace("|", "/").trim()).append(" | ");
            }
            sb.append("\n");
            if (i == 0) {
                sb.append("| ");
                for (int j = 0; j < row.getTableCells().size(); j++) sb.append("--- | ");
                sb.append("\n");
            }
        }
        return sb.append("\n").toString();
    }

    private String resizeAndBase64(byte[] data) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(data));
            if (original == null) return Base64.getEncoder().encodeToString(data);
            
            int max = 1024;
            if (original.getWidth() <= max && original.getHeight() <= max) 
                return Base64.getEncoder().encodeToString(data);

            double ratio = Math.min((double) max / original.getWidth(), (double) max / original.getHeight());
            int w = (int) (original.getWidth() * ratio);
            int h = (int) (original.getHeight() * ratio);

            java.awt.Image resized = original.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH);
            BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            output.getGraphics().drawImage(resized, 0, 0, null);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(output, "jpg", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(data);
        }
    }

    private String cleanText(String text) {
        return text.replaceAll("(?m)^[ \t]+$", "").replaceAll("\n{3,}", "\n\n").trim();
    }
}
