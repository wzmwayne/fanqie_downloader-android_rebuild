package com.example.fqdownloader;

import android.util.Log;

import com.example.fqdownloader.crypto.FqCrypto;
import com.example.fqdownloader.epub.EpubGenerator;
import com.example.fqdownloader.json.JsonParser;
import com.example.fqdownloader.json.JsonValue;
import com.example.fqdownloader.model.BookInfo;
import com.example.fqdownloader.model.Chapter;
import com.example.fqdownloader.model.ImageInfo;
import com.example.fqdownloader.jni.FqSigner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class DownloadEngine {

    private static final String TAG = "DownloadEngine";
    private static final String FQ_BASE_URL = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String FQ_UA_BASE = "com.dragon.read.oversea.gp/68132 (Linux; U; Android 10; zh_CN; OnePlus11; Build/V291IR;tt-ok/3.12.13.4-tiktok)";
    private static final String DIRECTORY_URL = "https://fanqienovel.com/api/reader/directory/detail";
    private static final String SEARCH_URL = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/";
    private static final int GROUP_SIZE = 50;
    private static final Pattern IMG_PATTERN = Pattern.compile(
        "<img[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", Pattern.CASE_INSENSITIVE);

    private final File outputDir;
    private final ProgressListener listener;

    private String FQ_UA = FQ_UA_BASE;
    private String FQ_COOKIE = "store-region=cn-zj; store-region-src=did; install_id=933935730456617";

    private String decryptKey;
    private String deviceId = "933935730452521";
    private String installId = "933935730456617";
    private String deviceCdid = "17f05006-423a-4172-be4b-7d26a42f2f4a";

    public interface ProgressListener {
        void onLog(String message);
        void onProgress(int current, int total, String batchInfo);
        void onError(String error);
        void onComplete(String outputPath);
    }

    public DownloadEngine(File outputDir, ProgressListener listener) {
        this.outputDir = outputDir;
        this.listener = listener;
    }

    public void search(String keyword) {
        new Thread(() -> {
            try {
                String url = SEARCH_URL + "?q=" + URLEncoder.encode(keyword, "UTF-8") + "&aid=1967";
                var json = fetchJson(url);
                var dataObj = json instanceof JsonValue.JsonObject jo ? jo.map().get("data") : null;
                var retData = dataObj != null ? optArray(dataObj, "ret_data") : new JsonValue.JsonArray(new ArrayList<>());

                var results = retData.list().stream()
                        .map(v -> new BookInfo(
                                optStr(v, "book_id"),
                                optStr(v, "title"),
                                optStr(v, "author"),
                                optStr(v, "category"),
                                optStr(v, "score", "N/A"),
                                optStr(v, "abstract")))
                        .collect(java.util.stream.Collectors.toList());

                if (results.isEmpty()) {
                    listener.onLog("未找到相关书籍。");
                    return;
                }
                listener.onLog("共找到 " + results.size() + " 本小说：\n");
                StringBuilder sb = new StringBuilder();
                for (var r : results) {
                    sb.append(String.format("ID: %s\n书名: %s\n作者: %s\n评分: %s\n\n",
                            r.bookId, r.title, r.author, r.score));
                }
                listener.onLog(sb.toString());
            } catch (Exception e) {
                listener.onError("搜索失败: " + e);
            }
        }).start();
    }

    public void download(String bookId, int start, int end, boolean outputTxt) {
        new Thread(() -> {
            try {
                var outDir = java.nio.file.Paths.get(outputDir.getAbsolutePath(), bookId);
                Files.createDirectories(outDir);

                listener.onLog("获取书籍信息...");
                String bookName = fetchBookNameFromPage(bookId);
                if (bookName.trim().isEmpty()) bookName = bookId;

                ImageInfo coverImage = null;
                try {
                    var coverUrl = fetchCoverUrl(bookId);
                    if (coverUrl != null) coverImage = downloadImage(coverUrl);
                } catch (Exception ignored) {}

                listener.onLog("获取目录...");
                var chapters = fetchChaptersWithFallback(bookId);
                if (chapters.isEmpty()) { listener.onError("获取目录失败"); return; }

                int total = chapters.size();
                int startIdx = Math.max(1, start);
                int endIdx = end <= 0 ? total : end;
                if (startIdx > total) { listener.onError("起始章节超出范围"); return; }
                if (endIdx > total) endIdx = total;
                if (startIdx > endIdx) { listener.onError("起始章节 > 结束章节"); return; }
                var selected = chapters.subList(startIdx - 1, endIdx);
                int totalChapters = selected.size();
                listener.onLog("下载第 " + startIdx + " ~ " + endIdx + " 章（共 " + totalChapters + " 章）");

                // Init signature engine
                listener.onLog("初始化签名引擎...");
                decryptKey = fetchDecryptKey();
                listener.onLog("密钥已就绪");

                var chapterContents = new ConcurrentHashMap<Integer, String>();
                var imageCache = new ConcurrentHashMap<String, ImageInfo>();

                for (int g = 0; g < totalChapters; g += GROUP_SIZE) {
                    int gEnd = Math.min(g + GROUP_SIZE, totalChapters);
                    List<Integer> failed = new ArrayList<>();
                    for (int j = g; j < gEnd; j++) failed.add(j);
                    listener.onLog("批次 " + ((g / GROUP_SIZE) + 1) + "/" + ((totalChapters + GROUP_SIZE - 1) / GROUP_SIZE));

                    while (!failed.isEmpty()) {
                        var ids = failed.stream().map(i -> selected.get(i).itemId).collect(java.util.stream.Collectors.joining(","));
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        try {
                            var bc = fetchBatchContent(ids, bookId);
                            var iter = failed.iterator();
                            while (iter.hasNext()) {
                                int j = iter.next();
                                String raw = bc != null ? bc.get(selected.get(j).itemId) : null;
                                if (raw == null || raw.trim().isEmpty()) continue;
                                String processed = outputTxt ? extractBlkText(raw) : blkToP(raw);
                                if (!outputTxt) processed = processImages(processed, imageCache);
                                chapterContents.put(j, processed);
                                iter.remove();
                            }
                        } catch (Exception e) {
                            listener.onLog("批次失败: " + e + "，" + failed.size() + " 章待重试");
                        }
                        int totalBatches = (totalChapters + GROUP_SIZE - 1) / GROUP_SIZE;
                        int currentBatch = (g / GROUP_SIZE) + 1;
                        listener.onProgress(currentBatch, totalBatches,
                                "批次 " + currentBatch + "/" + totalBatches);
                    }
                }

                var ext = outputTxt ? ".txt" : ".epub";
                var outputPath = outDir.resolve(bookId + "_" + startIdx + "-" + endIdx + ext);
                generateOutput(outputPath, bookId, bookName, selected, totalChapters, outputTxt,
                              chapterContents, imageCache, coverImage);
                listener.onComplete(outputPath.toAbsolutePath().toString());

            } catch (Exception e) {
                listener.onError("下载失败: " + e);
                Log.e(TAG, "download error", e);
            }
        }).start();
    }

    // ── FQ signed API ──

    private String fetchDecryptKey() throws Exception {
        FqCrypto crypto = new FqCrypto(FqCrypto.REG_KEY);
        String encContent = crypto.newRegisterKeyContent(deviceId, "0");
        String url = FQ_BASE_URL + "/reading/crypt/registerkey" + buildFqQS();
        String sig = getFqSig(url);
        byte[] raw = fqHttpPost(url, sig, ("{\"content\":\"" + encContent + "\",\"keyver\":1}").getBytes(StandardCharsets.UTF_8));
        if (raw == null) throw new IOException("registerkey 返回空");
        String resp = tryGunzip(raw);
        if (resp == null) resp = new String(raw, StandardCharsets.UTF_8);
        try {
            var parsed = new JsonParser(resp).parse();
            if (parsed instanceof JsonValue.JsonObject root) {
                int code = root.integer("code", 0);
                if (code != 0) {
                    String message = root.str("message", "unknown error");
                    switch (code) {
                        case 1 -> throw new IOException("SYSTEM: 系统错误");
                        case 2 -> throw new IOException("INVALID_REQ: 无效请求");
                        case 100 -> throw new IOException("FAST_REJECT: 请求被快速拒绝");
                        case 110 -> throw new IOException("ILLEGAL_ACCESS: 设备可能被封禁");
                        case 500000 -> throw new IOException("KEY_TOO_OLD: 密钥过期");
                        case 500001 -> throw new IOException("ESCAPE_KEY: 逃逸密钥");
                        case 500002 -> throw new IOException("VERIFY_FAIL: 验证失败");
                        case 500003 -> { return ""; }
                        default -> throw new IOException("registerkey error: " + message + " (code=" + code + ")");
                    }
                }
            }
        } catch (IOException e) { throw e; } catch (Exception ignored) {}
        int p = resp.indexOf("\"key\"");
        if (p < 0) throw new IOException("registerkey 响应无key字段");
        int c = resp.indexOf(':', p + 5);
        int sq = resp.indexOf('"', c + 1), eq = resp.indexOf('"', sq + 1);
        if (sq < 0 || eq <= sq) throw new IOException("key格式错误");
        String encryptedKey = resp.substring(sq + 1, eq);
        String realKey = FqCrypto.getRealKey(encryptedKey);
        return realKey.length() > 32 ? realKey.substring(0, 32) : realKey;
    }

    private Map<String, String> fetchBatchContent(String itemIds, String bookId) throws Exception {
        Map<String, String> params = buildFqParams();
        params.put("item_ids", itemIds);
        params.put("key_register_ts", "0");
        params.put("book_id", bookId);
        params.put("req_type", "1");
        String url = FQ_BASE_URL + "/reading/reader/batch_full/v1" + buildFqQS(params);
        String sig = getFqSig(url);
        byte[] raw = fqHttpGet(url, sig);
        if (raw == null || raw.length == 0) throw new IOException("batch_full 返回空");
        String json = tryGunzip(raw);
        if (json == null) json = new String(raw, StandardCharsets.UTF_8);
        Map<String, String> results = new LinkedHashMap<>();
        try {
            var parsed = new JsonParser(json).parse();
            if (parsed instanceof JsonValue.JsonObject root) {
                int code = root.integer("code", 0);
                if (code != 0) {
                    String message = root.str("message", "unknown error");
                    switch (code) {
                        case 100 -> throw new IOException("FAST_REJECT (code=100)");
                        case 110 -> throw new IOException("ILLEGAL_ACCESS (code=110)");
                        case 111 -> throw new IOException("HIT_VERIFY_CODE (code=111)");
                        case 101004 -> throw new IOException("BOOK_NOT_EXIST (code=101004)");
                        case 101005 -> throw new IOException("CHAPTER_DATA_GET_ERROR (code=101005)");
                        case 101009 -> throw new IOException("USER_NO_PERMISSION (code=101009)");
                        case 101017 -> throw new IOException("CONTENT_VERIFYING (code=101017)");
                        case 101021 -> throw new IOException("BOOK_FULLLY_REMOVE (code=101021)");
                        default -> {
                            if (code >= 100 && code <= 111)
                                throw new IOException("ReaderApiERR: " + message + " (code=" + code + ")");
                            var d = root.get("data");
                            if (!(d instanceof JsonValue.JsonObject) || ((JsonValue.JsonObject) d).map().isEmpty())
                                throw new IOException("响应错误 " + code + ": " + message);
                        }
                    }
                }
                var data = root.get("data");
                if (data instanceof JsonValue.JsonObject dataObj) {
                    for (var entry : dataObj.map().entrySet()) {
                        var itemId = entry.getKey();
                        if (entry.getValue() instanceof JsonValue.JsonObject itemObj) {
                            int cryptStatus = itemObj.integer("crypt_status", 0);
                            var contentStr = itemObj.str("content");
                            if (contentStr != null && !contentStr.trim().isEmpty()) {
                                try {
                                    String decrypted = switch (cryptStatus) {
                                        case 1, 2 -> contentStr;
                                        default -> FqCrypto.decryptAndDecompressContent(contentStr, decryptKey);
                                    };
                                    results.put(itemId, decrypted);
                                } catch (Exception ex) {
                                    results.put(itemId, "");
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("JSON解析失败: " + e); }
        return results;
    }

    // ── HTTP ──

    private byte[] fqHttpGet(String url, String sig) throws Exception {
        HttpURLConnection c = openFqConn(url);
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", FQ_UA); c.setRequestProperty("Cookie", FQ_COOKIE);
        if (sig != null && !sig.isEmpty()) {
            String[] lines = sig.split("\n");
            for (int i = 0; i < lines.length - 1; i += 2)
                c.setRequestProperty(lines[i].trim(), lines[i + 1].trim());
        }
        for (var e : buildFqHeaders().entrySet()) {
            if (!e.getKey().equals("Cookie") && !e.getKey().equals("User-Agent"))
                c.setRequestProperty(e.getKey(), e.getValue());
        }
        int code = c.getResponseCode();
        listener.onLog("  [HTTP] " + url.substring(0, Math.min(120, url.length())) + " → " + code);
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close(); return bos.toByteArray();
    }

    private byte[] fqHttpPost(String url, String sig, byte[] body) throws Exception {
        HttpURLConnection c = openFqConn(url);
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        if (sig != null && !sig.isEmpty()) {
            String[] lines = sig.split("\n");
            for (int i = 0; i < lines.length - 1; i += 2)
                c.setRequestProperty(lines[i].trim(), lines[i + 1].trim());
        }
        for (var e : buildFqHeaders().entrySet()) {
            if (!e.getKey().equals("Content-Type"))
                c.setRequestProperty(e.getKey(), e.getValue());
        }
        c.getOutputStream().write(body); c.getOutputStream().flush();
        int code = c.getResponseCode();
        listener.onLog("  [HTTP] POST " + url.substring(0, Math.min(120, url.length())) + " → " + code);
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close(); return bos.toByteArray();
    }

    private HttpURLConnection openFqConn(String url) throws Exception {
        return (HttpURLConnection) new URL(url).openConnection();
    }

    private synchronized String getFqSig(String url) {
        Map<String, String> h = buildFqHeaders();
        StringBuilder sb = new StringBuilder();
        for (var e : h.entrySet())
            sb.append(e.getKey()).append("\r\n").append(e.getValue()).append("\r\n");
        String hs = sb.toString();
        if (hs.endsWith("\r\n")) hs = hs.substring(0, hs.length() - 2);
        return FqSigner.generateSignature(url, hs);
    }

    private Map<String, String> buildFqParams() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("iid", installId); p.put("device_id", deviceId); p.put("ac", "wifi");
        p.put("channel", "googleplay"); p.put("aid", "1967"); p.put("app_name", "novelapp");
        p.put("version_code", "68132"); p.put("version_name", "6.8.1.32");
        p.put("device_platform", "android"); p.put("os", "android"); p.put("ssmix", "a");
        p.put("device_type", "OnePlus11"); p.put("device_brand", "OnePlus");
        p.put("language", "zh"); p.put("os_api", "32"); p.put("os_version", "12");
        p.put("manifest_version_code", "68132"); p.put("resolution", "3200*1440");
        p.put("dpi", "640"); p.put("update_version_code", "68132");
        p.put("_rticket", String.valueOf(System.currentTimeMillis()));
        p.put("host_abi", "arm64-v8a"); p.put("dragon_device_type", "phone");
        p.put("pv_player", "68132"); p.put("compliance_status", "0");
        p.put("need_personal_recommend", "1"); p.put("player_so_load", "1");
        p.put("is_android_pad_screen", "0"); p.put("rom_version", "V291IR+release-keys");
        p.put("cdid", deviceCdid);
        return p;
    }

    private String buildFqQS() { return buildFqQS(buildFqParams()); }

    private String buildFqQS(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private Map<String, String> buildFqHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cookie", FQ_COOKIE); h.put("User-Agent", FQ_UA);
        h.put("Accept", "application/json; charset=utf-8");
        h.put("Accept-Encoding", "gzip"); h.put("x-xs-from-web", "0");
        h.put("x-ss-req-ticket", String.valueOf(System.currentTimeMillis()));
        h.put("x-reading-request", System.currentTimeMillis() + "-" + (int)(Math.random() * 2e9));
        h.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
        h.put("lc", "101"); h.put("sdk-version", "2");
        h.put("passport-sdk-version", "50564");
        h.put("x-tt-store-region", "cn-zj"); h.put("x-tt-store-region-src", "did");
        return h;
    }

    private String tryGunzip(byte[] data) {
        if (data == null || data.length < 2) return null;
        if ((data[0] & 0xff) != 0x1f || (data[1] & 0xff) != 0x8b) return null;
        try {
            GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
            gis.close(); return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    // ── Content processing ──

    private String extractBlkText(String html) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        boolean first = true;
        while (true) {
            int st = html.indexOf("<blk", idx);
            if (st < 0) break;
            int ct = html.indexOf(">", st);
            if (ct < 0) break;
            int en = html.indexOf("</blk>", ct);
            if (en < 0) { sb.append(html.substring(ct + 1)); break; }
            String blk = html.substring(ct + 1, en);
            if (first) { first = false; idx = en + 6; continue; }
            sb.append(blk).append("\n");
            idx = en + 6;
        }
        String result = sb.toString().replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ");
        result = result.replaceAll("(?i)<br\\s*/?>", "\n");
        result = result.replaceAll("<[^>]+>", "");
        return result.trim();
    }

    private String blkToP(String html) {
        String result = html.replaceAll("(?i)<blk[^>]*>", "<p>");
        result = result.replaceAll("(?i)</blk>", "</p>");
        result = result.replaceAll("(?i)<imge[^>]*/>", "");
        return result;
    }

    private String processImages(String html, ConcurrentHashMap<String, ImageInfo> cache) {
        var matcher = IMG_PATTERN.matcher(html);
        var sb = new StringBuffer();
        while (matcher.find()) {
            String src = matcher.group(1);
            String newSrc = downloadAndCacheImage(src, cache);
            String tag = matcher.group(0);
            String rewritten = tag.replace(src, newSrc);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(rewritten));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private ImageInfo downloadImage(String url) {
        url = ensureAbsolute(url);
        if (url == null) return null;
        try {
            byte[] data = simpleHttpGet(url);
            var mimeAndExt = sniffMime(data);
            if (mimeAndExt[0].equals("application/octet-stream")) return null;
            String hash = sha1Hex(url);
            return new ImageInfo(data, mimeAndExt[0], "images/" + hash + mimeAndExt[1]);
        } catch (Exception ignored) {}
        return null;
    }

    private String downloadAndCacheImage(String url, ConcurrentHashMap<String, ImageInfo> cache) {
        url = ensureAbsolute(url);
        if (url == null) return null;
        var existing = cache.get(url);
        if (existing != null) return existing.filename;
        try {
            byte[] data = simpleHttpGet(url);
            var mimeAndExt = sniffMime(data);
            if (mimeAndExt[0].equals("application/octet-stream")) return url;
            String hash = sha1Hex(url);
            String filename = "images/" + hash + mimeAndExt[1];
            var info = new ImageInfo(data, mimeAndExt[0], filename);
            var prev = cache.putIfAbsent(url, info);
            return prev != null ? prev.filename : filename;
        } catch (Exception ignored) {}
        return url;
    }

    private String[] sniffMime(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8 && bytes[2] == (byte)0xFF)
            return new String[]{"image/jpeg", ".jpg"};
        if (bytes.length >= 8 && bytes[0] == (byte)0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47)
            return new String[]{"image/png", ".png"};
        if (bytes.length >= 6 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46)
            return new String[]{"image/gif", ".gif"};
        if (bytes.length >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46)
            return new String[]{"image/webp", ".webp"};
        return new String[]{"application/octet-stream", ""};
    }

    // ── Output ──

    private void generateOutput(Path outputPath, String bookId, String bookName,
                                 List<Chapter> selected, int totalChapters, boolean outputTxt,
                                 ConcurrentHashMap<Integer, String> chapterContents,
                                 ConcurrentHashMap<String, ImageInfo> imageCache,
                                 ImageInfo coverImage) throws Exception {
        if (outputTxt) {
            var sb = new StringBuilder();
            for (int i = 0; i < totalChapters; i++) {
                var content = chapterContents.get(i);
                if (content != null && !content.trim().isEmpty()) {
                    sb.append(selected.get(i).title).append("\n\n");
                    sb.append(content).append("\n\n\n");
                } else {
                    sb.append(selected.get(i).title).append("\n\n[获取失败]\n\n\n");
                }
            }
            Files.write(outputPath, sb.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            listener.onLog("\n生成 EPUB...");
            try (var fos = Files.newOutputStream(outputPath)) {
                EpubGenerator.generate(fos, bookId, bookName, selected, totalChapters,
                                       chapterContents, imageCache, coverImage);
            }
        }
    }

    // ── Helpers ──

    private byte[] simpleHttpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " for " + url);
        InputStream is = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close(); return bos.toByteArray();
    }

    private String fetchBookNameFromPage(String bookId) {
        try {
            var state = parsePageState(bookId);
            if (state instanceof JsonValue.JsonObject so) {
                var page = so.get("page");
                if (page instanceof JsonValue.JsonObject p)
                    return p.str("bookName");
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String fetchCoverUrl(String bookId) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/page/" + bookId);
        var ldUrl = extractLdJsonCover(html);
        if (ldUrl != null) return ldUrl;
        var state = parsePageState(bookId);
        if (state instanceof JsonValue.JsonObject jo) {
            var page = jo.get("page");
            if (page instanceof JsonValue.JsonObject p) {
                var url = p.str("thumbUrl");
                if (url != null && !url.trim().isEmpty()) return ensureAbsolute(url);
                var thumbUri = p.str("thumbUri");
                if (thumbUri != null && !thumbUri.trim().isEmpty()) return ensureAbsolute(thumbUri);
            }
        }
        return null;
    }

    private String ensureAbsolute(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        return "https://fanqienovel.com" + (url.startsWith("/") ? "" : "/") + url;
    }

    private List<Chapter> fetchChaptersWithFallback(String bookId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) Thread.sleep(1000L * attempt);
                var dJson = fetchJson(DIRECTORY_URL + "?bookId=" + bookId);
                var dData = dJson instanceof JsonValue.JsonObject djo ? djo.map().get("data") : null;
                var chapters = extractChapters(dData);
                if (!chapters.isEmpty()) return chapters;
            } catch (Exception ignored) {}
        }
        try {
            var state = parsePageState(bookId);
            if (state instanceof JsonValue.JsonObject so) {
                var page = so.get("page");
                if (page != null) {
                    var chapters = extractChapters(page);
                    if (!chapters.isEmpty()) return chapters;
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    private List<Chapter> extractChapters(JsonValue data) {
        var list = new ArrayList<Chapter>();
        if (data == null) return list;
        if (list.isEmpty()) {
            for (var key : new String[]{"chapterList", "chapter_list", "chapters", "items", "list", "item_list"}) {
                var arr = optArray(data, key);
                if (!arr.list().isEmpty()) {
                    int idx = 1;
                    for (var ch : arr.list()) {
                        var itemId = optStr(ch, "itemId");
                        if (itemId.trim().isEmpty()) itemId = optStr(ch, "item_id");
                        if (!itemId.trim().isEmpty()) list.add(new Chapter(itemId, optStr(ch, "title"), idx++));
                    }
                    break;
                }
            }
        }
        if (list.isEmpty()) {
            var cv = data instanceof JsonValue.JsonObject jo ? jo.map().get("chapterListWithVolume") : null;
            if (cv instanceof JsonValue.JsonArray cva) {
                for (var vol : cva.list()) {
                    if (vol instanceof JsonValue.JsonArray va) {
                        for (var ch : va.list()) {
                            var itemId = optStr(ch, "itemId");
                            var title = optStr(ch, "title");
                            var orderStr = optStr(ch, "realChapterOrder");
                            int order = 0;
                            try { order = Integer.parseInt(orderStr); } catch (Exception ignored) {}
                            if (!itemId.trim().isEmpty()) list.add(new Chapter(itemId, title, order));
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            var inner = data instanceof JsonValue.JsonObject jo ? jo.get("data") : null;
            if (inner instanceof JsonValue.JsonObject) {
                for (var key : new String[]{"list", "chapterList", "chapter_list", "items", "item_list", "chapters"}) {
                    var arr = optArray(inner, key);
                    if (!arr.list().isEmpty()) {
                        int idx = 1;
                        for (var ch : arr.list()) {
                            var itemId = optStr(ch, "itemId");
                            if (itemId.trim().isEmpty()) itemId = optStr(ch, "item_id");
                            if (!itemId.trim().isEmpty()) list.add(new Chapter(itemId, optStr(ch, "title"), idx++));
                        }
                        break;
                    }
                }
            }
        }
        return list;
    }

    private String fetchHtml(String url) throws Exception {
        return new String(simpleHttpGet(url), StandardCharsets.UTF_8);
    }

    private JsonValue fetchJson(String url) throws Exception {
        return new JsonParser(fetchHtml(url)).parse();
    }

    private JsonValue parsePageState(String bookId) throws Exception {
        var html = fetchHtml("https://fanqienovel.com/page/" + bookId);
        var nextData = extractScriptJson(html, "__NEXT_DATA__");
        if (nextData != null) return nextData;
        return extractScriptJson(html, "__INITIAL_STATE__");
    }

    private JsonValue extractScriptJson(String html, String scriptId) {
        int start = html.indexOf(scriptId);
        if (start < 0) return null;
        start = html.indexOf('{', start + scriptId.length());
        if (start < 0) return null;
        int depth = 0, end = start;
        boolean inStr = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inStr) { if (c == '\\') i++; else if (c == '"') inStr = false; }
            else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        if (end <= start) return null;
        try { return new JsonParser(html.substring(start, end)).parse(); } catch (Exception e) { return null; }
    }

    private String extractLdJsonCover(String html) {
        var m = Pattern.compile(
            "<script[^>]*type=\"application/ld\\+json\"[^>]*>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            try {
                var parsed = new JsonParser(m.group(1)).parse();
                if (parsed instanceof JsonValue.JsonObject jo) {
                    for (var key : java.util.Arrays.asList("image", "images")) {
                        var v = jo.map().get(key);
                        if (v instanceof JsonValue.JsonArray arr) {
                            for (var item : arr.list()) {
                                if (item instanceof JsonValue.JsonString s) {
                                    var url = s.value().trim();
                                    if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) return url;
                                }
                            }
                        } else if (v instanceof JsonValue.JsonString s) {
                            var url = s.value().trim();
                            if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) return url;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(input.hashCode()); }
    }

    private static String optStr(JsonValue val, String key) {
        return JsonValue.optString(val, key);
    }

    private static String optStr(JsonValue val, String key, String def) {
        String s = optStr(val, key);
        return s.isEmpty() ? def : s;
    }

    private static JsonValue.JsonArray optArray(JsonValue val, String key) {
        return JsonValue.optArray(val, key);
    }
}
