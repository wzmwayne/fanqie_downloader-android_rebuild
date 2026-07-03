package com.example.fqdownloader.epub;

import com.example.fqdownloader.model.Chapter;
import com.example.fqdownloader.model.ImageInfo;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EpubGenerator {

    public static void generate(OutputStream out, String bookId, String bookName,
                                 List<Chapter> chapters, int totalChapters,
                                 ConcurrentHashMap<Integer, String> contents,
                                 ConcurrentHashMap<String, ImageInfo> imageCache,
                                 ImageInfo coverImage) throws Exception {
        try (var zos = new ZipOutputStream(out)) {
            zos.setLevel(9);
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
            var me = new ZipEntry("mimetype");
            me.setMethod(ZipEntry.STORED); me.setSize(mimetype.length);
            me.setCompressedSize(mimetype.length);
            var crc = new CRC32(); crc.update(mimetype); me.setCrc(crc.getValue());
            zos.putNextEntry(me); zos.write(mimetype); zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/stylesheet.css"));
            zos.write(css().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            String coverHref = null;
            if (coverImage != null) {
                coverHref = "cover.xhtml";
                zos.putNextEntry(new ZipEntry("OEBPS/" + coverHref));
                zos.write(coverXhtml(bookName, coverImage.filename).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("OEBPS/" + coverImage.filename));
                zos.write(coverImage.data); zos.closeEntry();
                imageCache.putIfAbsent("__cover__", coverImage);
            }

            var itemIds = new java.util.ArrayList<String>();
            for (int i = 0; i < totalChapters; i++) {
                var ch = i < chapters.size() ? chapters.get(i) : null;
                String title = ch != null ? ch.title : "章节" + (i + 1);
                String body = contents.getOrDefault(i, "");
                if (body.trim().isEmpty()) body = "<p style=\"color:#999;\">（内容获取失败）</p>";
                String fname = String.format("chapter_%05d.xhtml", i + 1);
                zos.putNextEntry(new ZipEntry("OEBPS/" + fname));
                zos.write(chapterXhtml(title, body).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                itemIds.add(fname);
            }

            String navHref = "nav.xhtml";
            zos.putNextEntry(new ZipEntry("OEBPS/" + navHref));
            zos.write(navXhtml(bookName, chapters, totalChapters).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            for (var entry : imageCache.entrySet()) {
                if ("__cover__".equals(entry.getKey())) continue;
                var info = entry.getValue();
                zos.putNextEntry(new ZipEntry("OEBPS/" + info.filename));
                zos.write(info.data); zos.closeEntry();
            }

            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opfXml(bookId, bookName, chapters, imageCache, itemIds, coverHref, navHref, totalChapters)
                     .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(ncxXml(bookId, bookName, chapters, itemIds, totalChapters)
                     .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static String containerXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
             + "  <rootfiles>\n"
             + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
             + "  </rootfiles>\n"
             + "</container>\n";
    }

    private static String opfXml(String bookId, String bookName, List<Chapter> chapters,
                                  Map<String, ImageInfo> imageCache,
                                  List<String> itemIds, String coverHref, String navHref, int totalChapters) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"BookId\">\n");
        sb.append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
        sb.append("    <dc:identifier id=\"BookId\">").append(xmlEscape(bookId)).append("</dc:identifier>\n");
        sb.append("    <dc:title>").append(xmlEscape(bookName)).append("</dc:title>\n");
        sb.append("    <dc:language>zh</dc:language>\n");
        if (coverHref != null) sb.append("    <meta name=\"cover\" content=\"cover-image\"/>\n");
        sb.append("  </metadata>\n");
        sb.append("  <manifest>\n");
        sb.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        sb.append("    <item id=\"css\" href=\"stylesheet.css\" media-type=\"text/css\"/>\n");
        if (coverHref != null)
            sb.append("    <item id=\"cover\" href=\"").append(coverHref).append("\" media-type=\"application/xhtml+xml\"/>\n");
        if (navHref != null)
            sb.append("    <item id=\"nav\" href=\"").append(navHref).append("\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        for (int i = 0; i < totalChapters; i++)
            sb.append("    <item id=\"ch_").append(String.format("%05d", i + 1))
              .append("\" href=\"").append(itemIds.get(i)).append("\" media-type=\"application/xhtml+xml\"/>\n");
        int imgIdx = 0;
        for (var entry : imageCache.entrySet()) {
            var info = entry.getValue();
            var id = entry.getKey().equals("__cover__") ? "cover-image" : String.format("img_%04d", imgIdx++);
            sb.append("    <item id=\"").append(id).append("\" href=\"").append(info.filename)
              .append("\" media-type=\"").append(info.mime).append("\"");
            if (entry.getKey().equals("__cover__")) sb.append(" properties=\"cover-image\"");
            sb.append("/>\n");
        }
        sb.append("  </manifest>\n");
        sb.append("  <spine toc=\"ncx\" page-progression-direction=\"ltr\">\n");
        if (coverHref != null) sb.append("    <itemref idref=\"cover\"/>\n");
        if (navHref != null) sb.append("    <itemref idref=\"nav\"/>\n");
        for (int i = 0; i < totalChapters; i++)
            sb.append("    <itemref idref=\"ch_").append(String.format("%05d", i + 1)).append("\"/>\n");
        sb.append("  </spine>\n</package>\n");
        return sb.toString();
    }

    private static String ncxXml(String bookId, String bookName, List<Chapter> chapters,
                                  List<String> itemIds, int totalChapters) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.dtd.org/NISO/2005/DTD/ncx-2005-1.dtd\">\n");
        sb.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n");
        sb.append("  <head>\n");
        sb.append("    <meta name=\"dtb:uid\" content=\"").append(xmlEscape(bookId)).append("\"/>\n");
        sb.append("    <meta name=\"dtb:depth\" content=\"1\"/>\n");
        sb.append("    <meta name=\"dtb:totalPageCount\" content=\"0\"/>\n");
        sb.append("    <meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n");
        sb.append("  </head>\n");
        sb.append("  <docTitle><text>").append(xmlEscape(bookName)).append("</text></docTitle>\n");
        sb.append("  <navMap>\n");
        for (int i = 0; i < totalChapters; i++) {
            var ch = i < chapters.size() ? chapters.get(i) : null;
            String title = ch != null ? ch.title : "章节" + (i + 1);
            sb.append("    <navPoint id=\"navPoint-").append(i + 1).append("\">\n");
            sb.append("      <navLabel>\n");
            sb.append("        <text>").append(xmlEscape(title)).append("</text>\n");
            sb.append("      </navLabel>\n");
            sb.append("      <content src=\"").append(itemIds.get(i)).append("\"/>\n");
            sb.append("    </navPoint>\n");
        }
        sb.append("  </navMap>\n</ncx>\n");
        return sb.toString();
    }

    private static String chapterXhtml(String title, String body) {
        String escapedTitle = xmlEscape(title);
        String wrapped = body;
        if (!body.contains("<p") && !body.contains("<div") && !body.contains("<h")) {
            var w = new StringBuilder();
            for (String line : body.split("\n")) {
                line = line.trim();
                if (!line.isEmpty()) w.append("<p>").append(line).append("</p>\n");
            }
            wrapped = w.toString();
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<!DOCTYPE html>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh\" xml:lang=\"zh\">\n"
             + "<head><title>" + escapedTitle + "</title>\n"
             + "<link href=\"stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/></head>\n"
             + "<body>\n<h1>" + escapedTitle + "</h1>\n<div class=\"content\">\n"
             + wrapped + "\n</div>\n</body>\n</html>\n";
    }

    private static String navXhtml(String bookName, List<Chapter> chapters, int totalChapters) {
        var ol = new StringBuilder();
        for (int i = 0; i < totalChapters; i++) {
            var ch = i < chapters.size() ? chapters.get(i) : null;
            String title = ch != null ? ch.title : "章节" + (i + 1);
            ol.append("      <li><a href=\"chapter_").append(String.format("%05d.xhtml", i + 1))
              .append("\">").append(xmlEscape(title)).append("</a></li>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<!DOCTYPE html>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"zh\" xml:lang=\"zh\">\n"
             + "<head>\n"
             + "  <meta charset=\"utf-8\"/>\n"
             + "  <title>" + xmlEscape(bookName) + "</title>\n"
             + "  <link href=\"stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/>\n"
             + "</head>\n"
             + "<body>\n"
             + "  <nav epub:type=\"toc\" id=\"toc\">\n"
             + "    <h1 id=\"toc-title\">" + xmlEscape(bookName) + "</h1>\n"
             + "    <ol>\n"
             + ol
             + "    </ol>\n"
             + "  </nav>\n"
             + "</body>\n</html>\n";
    }

    private static String coverXhtml(String bookName, String imagePath) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<!DOCTYPE html>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh\" xml:lang=\"zh\">\n"
             + "<head><title>" + xmlEscape(bookName) + "</title>\n"
             + "<link href=\"stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/></head>\n"
             + "<body>\n<div style=\"text-align:center;padding:2em;\">\n"
             + "<img src=\"" + xmlEscape(imagePath) + "\" alt=\"Cover\" style=\"max-width:100%;height:auto;\"/>\n"
             + "</div>\n</body>\n</html>\n";
    }

    private static String css() {
        return "body { font-family: serif; line-height: 1.8; padding: 1em; color: #000; }\n"
             + "p { text-indent: 2em; margin: 0.3em 0; line-height: 1.8; }\n"
             + "h1 { text-align: center; font-size: 1.4em; margin: 1em 0; }\n"
             + "img { max-width: 100%; height: auto; display: block; margin: 0.5em auto; }\n"
             + ".content { max-width: 35em; margin: 0 auto; }\n";
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
