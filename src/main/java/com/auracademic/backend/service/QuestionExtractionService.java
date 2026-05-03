package com.auracademic.backend.service;

import com.auracademic.backend.dto.ParsedQuestion;
import com.auracademic.backend.model.Question;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
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
 * V3: Sua loi 4 dap an tren 1 dong, Unicode normalization, bo loc cau gia.
 */
@Service
public class QuestionExtractionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionExtractionService.class);

    @Autowired
    private GeminiService geminiService;

    /**
     * Nhan dien so thu tu cau hoi sau khi strip dau tieng Viet.
     * Match: "cau 1", "question 23:", "1."
     */
    private static final Pattern QUESTION_NUMBER_STRIPPED = Pattern.compile(
        "^\\s*(cau|question|item|bai)?\\s*(\\d+)\\s*([.:)\\-\u2013]*)\\s*(.*)$"
    );

    /**
     * Nhan dien MOT option: "A. text", "A) text", "A- text"
     */
    private static final Pattern SINGLE_OPTION = Pattern.compile(
        "^([A-Da-d])[.):–\\-]\\s*(.*)$"
    );

    /**
     * Tim vi tri chia cac option tren cung 1 dong.
     */
    private static final Pattern OPTION_SPLIT = Pattern.compile(
        "(?<=\\S)\\s+(?=[A-Da-d][.):–\\-]\\s*)"
    );

    /**
     * Kiem tra dong co BAT DAU bang nhan option hay khong.
     */
    private static final Pattern STARTS_WITH_OPTION = Pattern.compile(
        "^\\s*[A-Da-d][.):–\\-]\\s*.*"
    );

    /**
     * Strip diacritics: "C\u00e2u" -> "Cau", "c\u1ea7u" -> "cau".
     * Dung NFD de tach combining marks, roi xoa chung.
     */
    private static String stripDiacritics(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                         .replaceAll("\\p{M}", "");
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    public List<ParsedQuestion> extractFromFile(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) throw new IllegalArgumentException("Ten file khong hop le");
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) return extractFromDocx(file.getInputStream());
        if (lower.endsWith(".pdf"))  return extractFromPdf(file.getInputStream());
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
                // FIX: khong bo qua paragraph chi chua anh (text rong nhung co anh)
                if ((raw == null || raw.isBlank()) && image == null) continue;
                String text = (raw != null && !raw.isBlank())
                    ? Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
                    : "";
                boolean bold  = isBoldParagraph(para);
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
    // CORE PARSER
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

            // 1. NFC normalize
            text = Normalizer.normalize(text, Normalizer.Form.NFC).trim();

            // 2. Kiem tra bat dau cau hoi moi
            String stripped = stripDiacritics(text).toLowerCase();
            Matcher qm = QUESTION_NUMBER_STRIPPED.matcher(stripped);
            if (qm.matches()) {
                String prefix = qm.group(1);
                String punct = qm.group(3);
                // Chi chap nhan neu co prefix ("cau", "bai"...) hoac neu khong co prefix thi phai co dau cau (nhu "1.")
                if (prefix != null || !punct.isEmpty()) {
                    if (current != null) flush(current, qText, opts, currentImg, result);
                    
                    String afterLabel = qm.group(4);
                    // Giu nguyen text goc cua phan afterLabel bang cach cat tu chuoi GOC
                    // Do chieu dai prefix va number trong chuoi goc co the khac stripped, 
                    // ta dung regex de thay the dung phan dau:
                    String originalAfterLabel = text.replaceFirst(
                        "(?i)^\\s*(?:[Cc][\\p{L}]*u|[Qq]uestion|[Ii]tem|[Bb][\\p{L}]*i)?\\s*\\d+\\s*[.:)\\-\u2013]*\\s*", "").trim();
                    
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

            // 3. Kiem tra line nay co chua option khong
            if (STARTS_WITH_OPTION.matcher(text).matches()) {
                List<ParsedQuestion.ParsedOption> parsed = parseOptionLine(text, seg.bold);
                if (!parsed.isEmpty()) {
                    opts.addAll(parsed);
                    continue;
                }
            }

            // 4. Noi dung bo sung (multi-line hoac giai thich)
            if (!opts.isEmpty()) {
                // Neu da co option, append vao option CUOI CUNG (ho tro multi-line option hoac explanation)
                ParsedQuestion.ParsedOption lastOpt = opts.get(opts.size() - 1);
                String currentOptText = lastOpt.getText() != null ? lastOpt.getText() : "";
                if (!text.isEmpty()) {
                    lastOpt.setText(currentOptText + (currentOptText.isEmpty() ? "" : "\n") + text);
                }
                // Neu co anh trong doan nay, gan vao cau hoi neu cau hoi chua co anh
                if (seg.image != null && currentImg == null) {
                    currentImg = seg.image;
                }
            } else {
                // Chua co option, append vao noi dung cau hoi
                if (seg.image != null && currentImg == null) {
                    currentImg = seg.image;
                }
                if (!text.isEmpty()) {
                    if (qText.length() > 0) qText.append("\n");
                    qText.append(text);
                }
            }
        }

        // Luu cau cuoi
        if (current != null) flush(current, qText, opts, currentImg, result);

        // Loc cau gia: tat ca option deu rong
        result.removeIf(q -> q.getOptions() == null
            || q.getOptions().stream().allMatch(o -> o.getText() == null || o.getText().isBlank()));

        log.info("[Extract] Trich xuat {} cau hoi hop le", result.size());
        return result;
    }

    /**
     * Phan tich mot dong co the chua 1, 2, hoac 4 option.
     * Luon split truoc bang OPTION_SPLIT, sau do match tung phan.
     *
     * Vi du:
     *   "A. Cáo. B. Cào cào. C. Cỏ. D. Chim sẻ." -> 4 options
     *   "A. Cáo.    B. Cào cào."                   -> 2 options
     *   "A. Cáo."                                  -> 1 option
     */
    private List<ParsedQuestion.ParsedOption> parseOptionLine(String text, boolean bold) {
        List<ParsedQuestion.ParsedOption> list = new ArrayList<>();

        // Tach tai cac vi tri giua option
        String[] parts = OPTION_SPLIT.split(text.trim());

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            Matcher m = SINGLE_OPTION.matcher(part);
            if (!m.matches()) {
                // Thu them toan bo dong neu la phan dau khong co label (edge case)
                continue;
            }
            String label   = m.group(1).toUpperCase();
            String optText = m.group(2).trim();
            boolean correct = bold || optText.endsWith("*");
            if (optText.endsWith("*")) optText = optText.substring(0, optText.length() - 1).trim();

            // Tranh them trung label
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

    /**
     * Luu cau hoi, dam bao du 4 option, sap xep A-B-C-D.
     */
    private void flush(
        ParsedQuestion q,
        StringBuilder textBuilder,
        List<ParsedQuestion.ParsedOption> opts,
        String image,
        List<ParsedQuestion> list
    ) {
        String text = textBuilder.toString().trim();
        // FIX: luu ca cau chi co anh (text rong nhung co image + options)
        boolean hasOptions = opts.stream().anyMatch(o -> o.getText() != null && !o.getText().isBlank());
        if (text.isEmpty() && image == null && !hasOptions) return;
        // Neu text rong nhung co anh, dung placeholder
        if (text.isEmpty()) text = "(Xem hinh trong de)";

        q.setText(text);
        q.setImageBase64(image);

        // Gop nhom: giu option cuoi cung neu co trung label
        Map<String, ParsedQuestion.ParsedOption> dedupe = new LinkedHashMap<>();
        for (ParsedQuestion.ParsedOption o : opts) dedupe.put(o.getLabel(), o);

        // Dam bao co du A B C D (them rong neu thieu)
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
        return para.getRuns().stream()
            .anyMatch(r -> Boolean.TRUE.equals(r.isBold()) && r.text() != null && !r.text().isBlank());
    }

    private String extractImage(XWPFParagraph para) {
        try {
            for (XWPFRun run : para.getRuns()) {
                List<XWPFPicture> pics = run.getEmbeddedPictures();
                if (pics != null && !pics.isEmpty()) {
                    byte[] data = pics.get(0).getPictureData().getData();
                    String mime = pics.get(0).getPictureData().getPackagePart().getContentType();
                    if (mime == null || mime.isBlank()) mime = "image/png";
                    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
                }
            }
        } catch (Exception e) {
            log.warn("[Extract] Anh loi: {}", e.getMessage());
        }
        return null;
    }

    private String tableToMarkdown(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";
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
                return para.getText();
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            return parseNode(doc.getDocumentElement()).trim();
        } catch (Exception e) {
            log.warn("[Extract] Loi parse XML doan van co cong thuc Toan, fallback ve getText(): {}", e.getMessage());
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
            if (deg.isEmpty()) return "√(" + e + ")";
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

    // ─────────────────────────────────────────────────────────────────
    // PDF
    // ─────────────────────────────────────────────────────────────────

    private List<ParsedQuestion> extractFromPdf(InputStream is) throws Exception {
        byte[] bytes = is.readAllBytes();
        PDDocument pdDoc = Loader.loadPDF(bytes);
        PDFRenderer renderer = new PDFRenderer(pdDoc);
        List<String> base64Images = new ArrayList<>();
        
        log.info("[Extract] Rendering {} pages from PDF to images for AI processing...", pdDoc.getNumberOfPages());
        for (int i = 0; i < pdDoc.getNumberOfPages(); i++) {
            BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", baos);
            byte[] imageBytes = baos.toByteArray();
            base64Images.add(Base64.getEncoder().encodeToString(imageBytes));
        }
        pdDoc.close();

        String prompt = "Trích xuất TOÀN BỘ câu hỏi trắc nghiệm từ các hình ảnh trang PDF này. " +
                        "QUY TẮC BẮT BUỘC:\n" +
                        "1. CHUYỂN ĐỔI TẤT CẢ CÁC CÔNG THỨC TOÁN HỌC thành định dạng LaTeX (bọc trong `$ ... $` hoặc `$$ ... $$`).\n" +
                        "2. Giữ nguyên các định dạng bảng biểu nếu có bằng Markdown.\n" +
                        "3. Tách biệt phần câu hỏi và phần đáp án A, B, C, D rõ ràng.\n" +
                        "4. Đánh dấu đáp án đúng nếu có dấu hiệu trong ảnh, nếu không thì mặc định chọn A.";
        
        log.info("[Extract] Goi Gemini AI de phan tich PDF...");
        List<Question> aiQuestions = geminiService.refineQuestions(prompt, "", base64Images);
        
        List<ParsedQuestion> result = new ArrayList<>();
        int qCounter = 1;
        for (Question q : aiQuestions) {
            ParsedQuestion pq = new ParsedQuestion();
            pq.setId("q" + (qCounter++));
            pq.setText(q.getText());
            pq.setImageBase64(q.getImageUrl());
            
            List<ParsedQuestion.ParsedOption> options = new ArrayList<>();
            if (q.getOptions() != null) {
                for (com.auracademic.backend.model.Option o : q.getOptions()) {
                    ParsedQuestion.ParsedOption po = new ParsedQuestion.ParsedOption();
                    String lbl = (o.getId() != null && o.getId().length() == 1) ? o.getId().toUpperCase() : "?";
                    po.setId(o.getId() != null ? o.getId().toLowerCase() : "?");
                    po.setLabel(lbl);
                    po.setText(o.getText());
                    po.setCorrect(o.isCorrect());
                    options.add(po);
                }
            }
            pq.setOptions(options);
            result.add(pq);
        }
        
        return result;
    }

    private record Segment(String text, String image, boolean bold, boolean isTable) {}
}
