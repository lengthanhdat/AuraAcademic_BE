package com.auracademic.backend.service;

import com.auracademic.backend.dto.ParsedQuestion;
import com.auracademic.backend.model.Question;
import com.auracademic.backend.service.LiteLlmService;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;

/**
 * V4: Sử dụng PDFTextStripper cho PDF có chữ, fallback Gemini cho PDF scan ảnh.
 * Sửa lỗi text=null, 4 đáp án trên 1 dòng, Unicode normalization.
 */
@Service
public class QuestionExtractionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionExtractionService.class);

    @Autowired
    private LiteLlmService liteLlmService;

    private static final Pattern QUESTION_NUMBER_STRIPPED = Pattern.compile(
        "^\\s*(cau|question|item|bai)?\\s*(\\d+)\\s*([.:)\\-\u2013]*)\\s*(.*)$"
    );

    private static final Pattern SINGLE_OPTION = Pattern.compile(
        "^(?:\\*\\*)?([A-Da-d])(?:\\*\\*)?[.):–\\-]\\s*(.*)$"
    );

    private static final Pattern OPTION_SPLIT = Pattern.compile(
        "(?<=\\S)\\s+(?=(?:\\*\\*)?[A-Da-d](?:\\*\\*)?[.):–\\-]\\s*)"
    );

    private static final Pattern STARTS_WITH_OPTION = Pattern.compile(
        "^\\s*(?:\\*\\*)?[A-Da-d](?:\\*\\*)?[.):–\\-]\\s*.*"
    );

    private static String stripDiacritics(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                         .replaceAll("\\p{M}", "");
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    public List<ParsedQuestion> extractFromFile(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) throw new IllegalArgumentException("Tên file không hợp lệ");
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) return extractFromDocx(file.getInputStream());
        if (lower.endsWith(".pdf"))  return extractFromPdf(file.getBytes(), filename);
        throw new IllegalArgumentException("Chi ho tro DOCX va PDF. File: " + filename);
    }

    // ─────────────────────────────────────────────────────────────────
    // DOCX
    // ─────────────────────────────────────────────────────────────────

    private List<ParsedQuestion> extractFromDocx(InputStream is) throws Exception {
        IOUtils.setByteArrayMaxOverride(200_000_000);
        XWPFDocument doc = new XWPFDocument(is);
        List<Segment> segments = new ArrayList<>();
        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph para) {
                String raw = extractParagraphText(para);
                String image = extractImage(para);
                if ((raw == null || raw.isBlank()) && image == null) continue;
                String text = (raw != null && !raw.isBlank())
                    ? Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
                    : "";
                boolean bold = isBoldParagraph(para);
                segments.add(new Segment(text, image, bold, false));
            } else if (el instanceof XWPFTable tbl) {
                String md = tableToMarkdown(tbl);
                if (!md.isBlank()) segments.add(new Segment(md, null, false, true));
            }
        }
        doc.close();
        return parseSegments(segments);
    }

    // ─────────────────────────────────────────────────────────────────
    // PDF — dùng PDFTextStripper trước (không cần AI),
    //        fallback Gemini chỉ khi PDF là file scan (không có chữ)
    // ─────────────────────────────────────────────────────────────────

    private List<ParsedQuestion> extractFromPdf(byte[] bytes, String filename) throws Exception {
        try {
            PDDocument pdDoc = Loader.loadPDF(bytes);
            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(pdDoc);
            pdDoc.close();

            if (rawText != null && !rawText.isBlank()) {
                log.info("[Extract] PDF '{}' text extracted: {} chars", filename, rawText.length());
                List<Segment> segments = new ArrayList<>();
                for (String line : rawText.split("\n")) {
                    String normalized = Normalizer.normalize(line, Normalizer.Form.NFC).trim();
                    if (!normalized.isEmpty()) {
                        segments.add(new Segment(normalized, null, false, false));
                    }
                }
                List<ParsedQuestion> result = parseSegments(segments);
                if (!result.isEmpty()) {
                    log.info("[Extract] PDF text parsing: {} câu hỏi", result.size());
                    return result;
                }
                log.warn("[Extract] PDF text parsed 0 câu hỏi, thử Gemini fallback...");
            } else {
                log.warn("[Extract] PDF '{}' text rỗng (có thể là PDF scan), thử Gemini fallback...", filename);
            }

            return extractFromPdfWithAI(bytes);

        } catch (Exception e) {
            log.error("[Extract] Lỗi extract PDF '{}': {}", filename, e.getMessage());
            throw e;
        }
    }

    /** Fallback cho PDF scan (chỉ có ảnh): dùng Gemini Vision để nhận diện chữ */
    private List<ParsedQuestion> extractFromPdfWithAI(byte[] bytes) throws Exception {
        PDDocument pdDoc = Loader.loadPDF(bytes);
        PDFRenderer renderer = new PDFRenderer(pdDoc);
        List<String> base64Images = new ArrayList<>();

        log.info("[Extract] Rendering {} trang PDF thành ảnh cho Gemini...", pdDoc.getNumberOfPages());
        for (int i = 0; i < pdDoc.getNumberOfPages(); i++) {
            BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", baos);
            base64Images.add(Base64.getEncoder().encodeToString(baos.toByteArray()));
        }
        pdDoc.close();

        String prompt = "Trích xuất TOÀN BỘ câu hỏi trắc nghiệm từ các hình ảnh trang PDF này. " +
                        "Tách biệt phần câu hỏi và phần đáp án A, B, C, D rõ ràng. " +
                        "Đánh dấu đáp án đúng nếu có dấu hiệu trong ảnh (tô đậm, gạch chân, dấu sao).";

        log.info("[Extract] Gọi LiteLLM Vision để phân tích PDF scan...");
        List<Question> aiQuestions = liteLlmService.refineQuestions(prompt, "", base64Images);

        List<ParsedQuestion> result = new ArrayList<>();
        int qCounter = 1;
        for (Question q : aiQuestions) {
            ParsedQuestion pq = new ParsedQuestion();
            pq.setId("q" + (qCounter++));
            // Luôn đảm bảo text không null
            String qText = (q.getText() != null && !q.getText().isBlank())
                ? q.getText()
                : "(Câu hỏi từ PDF scan - vui lòng nhập thủ công)";
            pq.setText(qText);
            pq.setImageBase64(q.getImageUrl());

            List<ParsedQuestion.ParsedOption> options = new ArrayList<>();
            if (q.getOptions() != null) {
                for (com.auracademic.backend.model.Option o : q.getOptions()) {
                    ParsedQuestion.ParsedOption po = new ParsedQuestion.ParsedOption();
                    String lbl = (o.getId() != null && o.getId().length() == 1)
                        ? o.getId().toUpperCase() : "?";
                    po.setId(o.getId() != null ? o.getId().toLowerCase() : "?");
                    po.setLabel(lbl);
                    po.setText(o.getText() != null ? o.getText() : "");
                    po.setCorrect(o.isCorrect());
                    options.add(po);
                }
            }
            pq.setOptions(options);
            result.add(pq);
        }

        log.info("[Extract] Gemini Vision trích xuất {} câu hỏi từ PDF scan", result.size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    // CORE PARSER (dùng chung cho DOCX và PDF text)
    // ─────────────────────────────────────────────────────────────────

    private List<ParsedQuestion> parseSegments(List<Segment> segments) {
        List<ParsedQuestion> result = new ArrayList<>();
        ParsedQuestion current = null;
        StringBuilder qText  = new StringBuilder();
        List<ParsedQuestion.ParsedOption> opts = new ArrayList<>();
        String currentImg = null;
        int qCounter = 0;

        for (Segment seg : segments) {
            String text = seg.text;
            text = Normalizer.normalize(text, Normalizer.Form.NFC).trim();

            String cleanText = text.replace("**", "").trim();
            String stripped = stripDiacritics(cleanText).toLowerCase();
            Matcher qm = QUESTION_NUMBER_STRIPPED.matcher(stripped);
            if (qm.matches()) {
                String prefix = qm.group(1);
                String punct  = qm.group(3);
                if (prefix != null || !punct.isEmpty()) {
                    if (current != null) flush(current, qText, opts, currentImg, result);
                    String originalAfterLabel = text.replaceFirst(
                        "(?i)^\\s*(?:\\*\\*)?\\s*(?:[Cc][\\p{L}]*u|[Qq]uestion|[Ii]tem|[Bb][\\p{L}]*i)?\\s*(?:\\*\\*)?\\s*\\d+\\s*(?:\\*\\*)?\\s*[.:)\\-\u2013]*\\s*(?:\\*\\*)?\\s*", "").trim();
                    qCounter++;
                    current = new ParsedQuestion();
                    current.setId("q" + qCounter);
                    qText = new StringBuilder(originalAfterLabel);
                    opts = new ArrayList<>();
                    currentImg = seg.image;
                    continue;
                }
            }

            if (current == null) continue;

            if (STARTS_WITH_OPTION.matcher(cleanText).matches()) {
                List<ParsedQuestion.ParsedOption> parsed = parseOptionLine(text, seg.bold);
                if (!parsed.isEmpty()) {
                    opts.addAll(parsed);
                    continue;
                }
            }

            if (!opts.isEmpty()) {
                ParsedQuestion.ParsedOption lastOpt = opts.get(opts.size() - 1);
                String currentOptText = lastOpt.getText() != null ? lastOpt.getText() : "";
                if (!text.isEmpty()) {
                    lastOpt.setText(currentOptText + (currentOptText.isEmpty() ? "" : "\n") + text);
                }
                if (seg.image != null && currentImg == null) currentImg = seg.image;
            } else {
                if (seg.image != null && currentImg == null) currentImg = seg.image;
                if (!text.isEmpty()) {
                    if (qText.length() > 0) qText.append("\n");
                    qText.append(text);
                }
            }
        }

        if (current != null) flush(current, qText, opts, currentImg, result);

        result.removeIf(q -> q.getOptions() == null
            || q.getOptions().stream().allMatch(o -> o.getText() == null || o.getText().isBlank()));

        log.info("[Extract] Trích xuất {} câu hỏi hợp lệ", result.size());
        return result;
    }

    private List<ParsedQuestion.ParsedOption> parseOptionLine(String text, boolean bold) {
        List<ParsedQuestion.ParsedOption> list = new ArrayList<>();
        String[] parts = OPTION_SPLIT.split(text.trim());
        
        int boldCount = 0;
        int totalOptions = 0;
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            totalOptions++;
            if (part.contains("**")) {
                boldCount++;
            }
        }
        
        // Chỉ coi bold là đáp án đúng nếu số lượng đáp án in đậm nhỏ hơn tổng số đáp án trên dòng đó
        // (để tránh trường hợp giáo viên in đậm toàn bộ cả câu hỏi/dòng đáp án)
        boolean treatBoldAsCorrect = boldCount > 0 && boldCount < totalOptions;

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            Matcher m = SINGLE_OPTION.matcher(part);
            if (!m.matches()) continue;
            String label   = m.group(1).toUpperCase();
            String optText = m.group(2).trim();
            
            boolean isBold = part.contains("**") || optText.contains("**");
            boolean correct = (treatBoldAsCorrect && isBold) || optText.endsWith("*");
            
            if (optText.endsWith("*")) {
                optText = optText.substring(0, optText.length() - 1).trim();
            }
            
            // Xóa tất cả marker markdown bold "**" để nội dung đáp án hiển thị bình thường cho học sinh
            optText = optText.replace("**", "").trim();
            
            boolean duplicate = list.stream().anyMatch(o -> o.getLabel().equals(label));
            if (duplicate) continue;
            ParsedQuestion.ParsedOption opt = new ParsedQuestion.ParsedOption();
            opt.setId(label.toLowerCase());
            opt.setLabel(label);
            opt.setText(optText);
            opt.setCorrect(correct);
            list.add(opt);
        }
        return list;
    }

    private void flush(
        ParsedQuestion q,
        StringBuilder textBuilder,
        List<ParsedQuestion.ParsedOption> opts,
        String image,
        List<ParsedQuestion> list
    ) {
        String text = textBuilder.toString().trim();
        boolean hasOptions = opts.stream().anyMatch(o -> o.getText() != null && !o.getText().isBlank());
        if (text.isEmpty() && image == null && !hasOptions) return;
        // Luôn đảm bảo text không bao giờ là null
        if (text.isEmpty()) text = "(Xem hinh trong de)";

        q.setText(text);
        q.setImageBase64(image);

        Map<String, ParsedQuestion.ParsedOption> dedupe = new LinkedHashMap<>();
        for (ParsedQuestion.ParsedOption o : opts) dedupe.put(o.getLabel(), o);

        String[] labels = {"A", "B", "C", "D"};
        for (String lbl : labels) {
            if (!dedupe.containsKey(lbl)) {
                ParsedQuestion.ParsedOption empty = new ParsedQuestion.ParsedOption();
                empty.setId(lbl.toLowerCase()); empty.setLabel(lbl);
                empty.setText(""); empty.setCorrect(false);
                dedupe.put(lbl, empty);
            }
        }

        List<ParsedQuestion.ParsedOption> sorted = new ArrayList<>(dedupe.values());
        sorted.sort(Comparator.comparing(ParsedQuestion.ParsedOption::getLabel));
        q.setOptions(sorted);
        list.add(q);
    }

    // ─────────────────────────────────────────────────────────────────
    // POI HELPERS
    // ─────────────────────────────────────────────────────────────────

    private boolean isBoldParagraph(XWPFParagraph para) {
        return para.getRuns().stream().anyMatch(r -> r.isBold());
    }

    private String extractImage(XWPFParagraph para) {
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pics = run.getEmbeddedPictures();
            if (pics != null && !pics.isEmpty()) {
                try {
                    byte[] data = pics.get(0).getPictureData().getData();
                    return Base64.getEncoder().encodeToString(data);
                } catch (Exception e) {
                    log.warn("[Extract] Không thể đọc ảnh từ DOCX: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    private String tableToMarkdown(XWPFTable tbl) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = tbl.getRows();
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            sb.append("| ");
            for (XWPFTableCell c : cells) sb.append(c.getText().replace("|", "\\|")).append(" | ");
            sb.append("\n");
            if (r == 0) {
                sb.append("|");
                cells.forEach(c -> sb.append(" --- |"));
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────────
    // MATH EXTRACTOR
    // ─────────────────────────────────────────────────────────────────

    private String extractParagraphText(XWPFParagraph para) {
        try {
            String xml = para.getCTP().xmlText();
            if (!xml.contains("oMath")) {
                StringBuilder sb = new StringBuilder();
                for (XWPFRun run : para.getRuns()) {
                    String text = run.toString();
                    if (text != null && !text.isEmpty()) {
                        if (run.isBold()) {
                            sb.append("**").append(text).append("**");
                        } else {
                            sb.append(text);
                        }
                    }
                }
                return sb.toString();
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            return parseNode(doc.getDocumentElement()).trim();
        } catch (Exception e) {
            log.warn("[Extract] Lỗi parse XML công thức Toán, fallback getText(): {}", e.getMessage());
            return para.getText();
        }
    }

    private String parseNode(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE) return node.getNodeValue();
        String name = getLocalName(node);

        if ("f".equals(name)) {
            String num = "", den = "";
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String childName = getLocalName(child);
                if ("num".equals(childName)) num = parseNode(child);
                else if ("den".equals(childName)) den = parseNode(child);
            }
            return "(" + num + ")/(" + den + ")";
        } else if ("sSup".equals(name)) {
            String base = "", sup = "";
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String childName = getLocalName(child);
                if ("e".equals(childName)) base = parseNode(child);
                else if ("sup".equals(childName)) sup = parseNode(child);
            }
            return base + "^(" + sup + ")";
        } else if ("sSub".equals(name)) {
            String base = "", sub = "";
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String childName = getLocalName(child);
                if ("e".equals(childName)) base = parseNode(child);
                else if ("sub".equals(childName)) sub = parseNode(child);
            }
            return base + "_(" + sub + ")";
        } else if ("sSubSup".equals(name)) {
            String base = "", sub = "", sup = "";
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String childName = getLocalName(child);
                if ("e".equals(childName)) base = parseNode(child);
                else if ("sub".equals(childName)) sub = parseNode(child);
                else if ("sup".equals(childName)) sup = parseNode(child);
            }
            return base + "_(" + sub + ")^(" + sup + ")";
        } else if ("rad".equals(name)) {
            String deg = "", e = "";
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String childName = getLocalName(child);
                if ("deg".equals(childName)) deg = parseNode(child);
                else if ("e".equals(childName)) e = parseNode(child);
            }
            if (deg.isEmpty()) return "\u221a(" + e + ")";
            return "root(" + deg + ")(" + e + ")";
        } else if ("t".equals(name)) {
            return node.getTextContent();
        } else if ("tab".equals(name)) {
            return "\t";
        } else if ("br".equals(name)) {
            return "\n";
        } else if ("numPr".equals(name) || "pPr".equals(name) || "rPr".equals(name)) {
            return "";
        } else {
            StringBuilder sb = new StringBuilder();
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                sb.append(parseNode(children.item(i)));
            }
            return sb.toString();
        }
    }

    private String getLocalName(Node node) {
        String name = node.getLocalName();
        if (name == null) name = node.getNodeName();
        if (name != null && name.contains(":")) {
            name = name.substring(name.indexOf(":") + 1);
        }
        return name;
    }

    private record Segment(String text, String image, boolean bold, boolean isTable) {}
}
