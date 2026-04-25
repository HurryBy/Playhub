package com.tvbox.web.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Service
public class LiveSourceService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"|([A-Za-z0-9_-]+)=([^\\s,]+)");
    private static final DateTimeFormatter XMLTV_DATE_TIME =
            new DateTimeFormatterBuilder().appendPattern("yyyyMMddHHmmss").toFormatter(Locale.ROOT);
    private static final DateTimeFormatter XMLTV_DATE_TIME_MINUTE =
            new DateTimeFormatterBuilder().appendPattern("yyyyMMddHHmm").toFormatter(Locale.ROOT);
    private static final DateTimeFormatter XMLTV_DATE_TIME_WITH_ZONE =
            new DateTimeFormatterBuilder().appendPattern("yyyyMMddHHmmss Z").toFormatter(Locale.ROOT);
    private static final DateTimeFormatter XMLTV_DATE_TIME_MINUTE_WITH_ZONE =
            new DateTimeFormatterBuilder().appendPattern("yyyyMMddHHmm Z").toFormatter(Locale.ROOT);

    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public LiveSourceService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> bootstrap(String rawPlaylistUrl, String rawEpgUrl) {
        String playlistUrl = normalizeExternalUrl(rawPlaylistUrl, "直播源地址不能为空");
        String epgUrl = StringUtils.hasText(rawEpgUrl) ? normalizeExternalUrl(rawEpgUrl, "EPG 地址不能为空") : "";
        String cacheKey = playlistUrl + "\n" + epgUrl;

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.payload();
        }

        List<LiveChannel> channels = parsePlaylist(playlistUrl, fetchBytes(playlistUrl, "直播源"));
        if (channels.isEmpty()) {
            throw new IllegalStateException("直播源未解析到任何频道");
        }

        String epgError = null;
        int epgProgramCount = 0;
        int epgMatchedChannels = 0;
        if (StringUtils.hasText(epgUrl)) {
            try {
                EpgParseResult epg = parseEpg(epgUrl, fetchBytes(epgUrl, "EPG"), channels);
                epgProgramCount = epg.programCount();
                epgMatchedChannels = epg.matchedChannelCount();
                applyEpg(channels, epg);
            } catch (Exception ex) {
                epgError = "EPG 加载失败: " + sanitizeMessage(ex);
            }
        }

        Map<String, Object> payload = buildResponse(channels, epgUrl, epgProgramCount, epgMatchedChannels, epgError);
        cache.put(cacheKey, new CacheEntry(Instant.now(), payload));
        return payload;
    }

    private Map<String, Object> buildResponse(List<LiveChannel> channels,
                                              String epgUrl,
                                              int epgProgramCount,
                                              int epgMatchedChannels,
                                              String epgError) {
        Map<String, Integer> groupCounter = new LinkedHashMap<>();
        List<Map<String, Object>> channelPayload = new ArrayList<>(channels.size());
        for (LiveChannel channel : channels) {
            groupCounter.merge(channel.group(), 1, Integer::sum);
            channelPayload.add(toChannelPayload(channel));
        }

        List<Map<String, Object>> groups = new ArrayList<>(groupCounter.size());
        for (Map.Entry<String, Integer> entry : groupCounter.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("count", entry.getValue());
            groups.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("channelCount", channels.size());
        summary.put("groupCount", groups.size());
        summary.put("epgLoaded", StringUtils.hasText(epgUrl) && epgError == null);
        summary.put("epgMatchedChannels", epgMatchedChannels);
        summary.put("epgProgramCount", epgProgramCount);
        summary.put("fetchedAt", Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("summary", summary);
        payload.put("groups", groups);
        payload.put("channels", channelPayload);
        if (epgError != null) {
            payload.put("epgError", epgError);
        }
        return payload;
    }

    private Map<String, Object> toChannelPayload(LiveChannel channel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", channel.id());
        row.put("name", channel.name());
        row.put("group", channel.group());
        row.put("url", channel.url());
        row.put("logo", channel.logo());
        row.put("tvgId", channel.tvgId());
        row.put("tvgName", channel.tvgName());
        row.put("epgChannelId", channel.epgChannelId());

        List<Map<String, Object>> programmes = new ArrayList<>(channel.programmes().size());
        for (LiveProgramme programme : channel.programmes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("start", programme.start());
            item.put("end", programme.end());
            item.put("title", programme.title());
            item.put("subTitle", programme.subTitle());
            item.put("desc", programme.desc());
            item.put("category", programme.category());
            programmes.add(item);
        }
        row.put("programmes", programmes);
        return row;
    }

    private void applyEpg(List<LiveChannel> channels, EpgParseResult epg) {
        for (int i = 0; i < channels.size(); i++) {
            LiveChannel channel = channels.get(i);
            String matchedEpgId = epg.channelToEpgId().get(channel.id());
            List<LiveProgramme> programmes = epg.programmesByChannel().getOrDefault(channel.id(), Collections.emptyList());
            String logo = StringUtils.hasText(channel.logo())
                    ? channel.logo()
                    : epg.epgIconByChannel().getOrDefault(channel.id(), "");
            channels.set(i, channel.withEpg(matchedEpgId, logo, programmes));
        }
    }

    private List<LiveChannel> parsePlaylist(String playlistUrl, byte[] contentBytes) {
        String content = decodeText(contentBytes).replace("\uFEFF", "");
        String[] lines = content.split("\\r?\\n");
        List<LiveChannel> channels = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        PendingChannel pending = null;
        String currentGroup = "未分组";
        int counter = 0;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }

            if (line.startsWith("#EXTINF")) {
                pending = parseExtInf(line);
                if (!StringUtils.hasText(pending.group()) && StringUtils.hasText(currentGroup)) {
                    pending = pending.withGroup(currentGroup);
                }
                continue;
            }

            if (line.startsWith("#EXTGRP:")) {
                currentGroup = normalizeGroup(line.substring("#EXTGRP:".length()).trim());
                if (pending != null && !StringUtils.hasText(pending.group())) {
                    pending = pending.withGroup(currentGroup);
                }
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            if (pending == null && line.contains(",")) {
                int commaIndex = line.indexOf(',');
                String first = line.substring(0, commaIndex).trim();
                String second = line.substring(commaIndex + 1).trim();
                if ("#genre#".equalsIgnoreCase(second)) {
                    currentGroup = normalizeGroup(first);
                    continue;
                }
                if (StringUtils.hasText(first) && StringUtils.hasText(second)) {
                    channels.add(buildChannel(
                            usedIds,
                            counter++,
                            playlistUrl,
                            new PendingChannel("", first, "", "", currentGroup),
                            second));
                    continue;
                }
            }

            channels.add(buildChannel(usedIds, counter++, playlistUrl, pending, line));
            pending = null;
        }

        return channels;
    }

    private LiveChannel buildChannel(Set<String> usedIds,
                                     int counter,
                                     String playlistUrl,
                                     PendingChannel pending,
                                     String rawUrl) {
        PendingChannel resolved = pending == null ? new PendingChannel("", "", "", "", "未分组") : pending;
        String url = resolveAgainst(playlistUrl, rawUrl);
        String group = normalizeGroup(resolved.group());
        String name = firstText(
                resolved.title(),
                resolved.tvgName(),
                resolved.tvgId(),
                deriveNameFromUrl(url),
                "频道 " + (counter + 1)
        );
        String logo = maybeResolveAssetUrl(playlistUrl, resolved.logo());
        String tvgId = safeText(resolved.tvgId());
        String tvgName = safeText(resolved.tvgName());
        String baseId = buildStableChannelId(tvgId, tvgName, name, group, url);
        String id = baseId;
        int suffix = 1;
        while (!usedIds.add(id)) {
            id = baseId + "-" + suffix++;
        }
        return new LiveChannel(id, name, group, url, logo, tvgId, tvgName, "", new ArrayList<>());
    }

    private PendingChannel parseExtInf(String line) {
        String body = line.substring("#EXTINF:".length()).trim();
        int commaIndex = findUnquotedComma(body);
        String meta = commaIndex >= 0 ? body.substring(0, commaIndex).trim() : body;
        String title = commaIndex >= 0 ? body.substring(commaIndex + 1).trim() : "";
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(meta);
        while (matcher.find()) {
            String key = safeText(firstText(matcher.group(1), matcher.group(3))).toLowerCase(Locale.ROOT);
            String value = safeText(firstText(matcher.group(2), matcher.group(4)));
            if (StringUtils.hasText(key) && !attrs.containsKey(key)) {
                attrs.put(key, value);
            }
        }
        return new PendingChannel(
                safeText(attrs.get("tvg-id")),
                safeText(title),
                safeText(attrs.get("tvg-name")),
                safeText(attrs.get("tvg-logo")),
                safeText(attrs.get("group-title"))
        );
    }

    private EpgParseResult parseEpg(String epgUrl, byte[] epgBytes, List<LiveChannel> channels) {
        Map<String, List<String>> byTvgId = new LinkedHashMap<>();
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (LiveChannel channel : channels) {
            index(byTvgId, normalizeChannelKey(channel.tvgId()), channel.id());
            index(byName, normalizeChannelKey(channel.name()), channel.id());
            index(byName, normalizeChannelKey(channel.tvgName()), channel.id());
        }

        Map<String, List<String>> epgToChannelIds = new LinkedHashMap<>();
        Map<String, String> firstEpgIdByChannel = new LinkedHashMap<>();
        Map<String, String> epgIconByChannel = new LinkedHashMap<>();
        Map<String, List<LiveProgramme>> programmesByChannel = new LinkedHashMap<>();

        long now = System.currentTimeMillis();
        long minTime = now - Duration.ofHours(6).toMillis();
        long maxTime = now + Duration.ofHours(36).toMillis();

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        String currentChannelId = null;
        List<String> currentDisplayNames = null;
        String currentIcon = "";
        MutableProgramme currentProgramme = null;
        String activeField = null;
        StringBuilder textBuffer = new StringBuilder();

        try (ByteArrayInputStream input = new ByteArrayInputStream(unwrapCompressed(epgBytes, epgUrl, HttpHeaders.of(Map.of(), (a, b) -> true)))) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("channel".equals(localName)) {
                        currentChannelId = safeText(reader.getAttributeValue(null, "id"));
                        currentDisplayNames = new ArrayList<>();
                        currentIcon = "";
                    } else if (currentChannelId != null && "display-name".equals(localName)) {
                        activeField = "display-name";
                        textBuffer.setLength(0);
                    } else if (currentChannelId != null && "icon".equals(localName)) {
                        currentIcon = safeText(reader.getAttributeValue(null, "src"));
                    } else if ("programme".equals(localName)) {
                        currentProgramme = new MutableProgramme(
                                safeText(reader.getAttributeValue(null, "channel")),
                                safeText(reader.getAttributeValue(null, "start")),
                                safeText(reader.getAttributeValue(null, "stop"))
                        );
                    } else if (currentProgramme != null && isProgrammeField(localName)) {
                        activeField = localName;
                        textBuffer.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (activeField != null) {
                        textBuffer.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("display-name".equals(localName) && currentChannelId != null) {
                        String value = safeText(textBuffer.toString());
                        if (StringUtils.hasText(value)) {
                            currentDisplayNames.add(value);
                        }
                        activeField = null;
                        textBuffer.setLength(0);
                    } else if ("channel".equals(localName) && currentChannelId != null) {
                        Set<String> matchedChannels = matchChannels(currentChannelId, currentDisplayNames, byTvgId, byName);
                        if (!matchedChannels.isEmpty()) {
                            List<String> channelIds = new ArrayList<>(matchedChannels);
                            epgToChannelIds.put(currentChannelId, channelIds);
                            for (String channelId : channelIds) {
                                firstEpgIdByChannel.putIfAbsent(channelId, currentChannelId);
                                if (StringUtils.hasText(currentIcon)) {
                                    epgIconByChannel.putIfAbsent(channelId, maybeResolveAssetUrl(epgUrl, currentIcon));
                                }
                            }
                        }
                        currentChannelId = null;
                        currentDisplayNames = null;
                        currentIcon = "";
                    } else if (currentProgramme != null && isProgrammeField(localName)) {
                        currentProgramme.accept(localName, textBuffer.toString());
                        activeField = null;
                        textBuffer.setLength(0);
                    } else if ("programme".equals(localName) && currentProgramme != null) {
                        List<String> matchedChannels = epgToChannelIds.getOrDefault(currentProgramme.channelId, Collections.emptyList());
                        if (!matchedChannels.isEmpty()) {
                            long start = parseXmltvTime(currentProgramme.start);
                            long end = parseXmltvTime(currentProgramme.stop);
                            if (start > 0 && end > start && end >= minTime && start <= maxTime) {
                                LiveProgramme programme = new LiveProgramme(
                                        start,
                                        end,
                                        firstText(currentProgramme.title, "未命名节目"),
                                        safeText(currentProgramme.subTitle),
                                        limitText(currentProgramme.desc, 280),
                                        safeText(currentProgramme.category)
                                );
                                for (String channelId : matchedChannels) {
                                    List<LiveProgramme> items = programmesByChannel.computeIfAbsent(channelId, ignored -> new ArrayList<>());
                                    if (items.size() < 32) {
                                        items.add(programme);
                                    }
                                }
                            }
                        }
                        currentProgramme = null;
                    }
                }
            }
        } catch (IOException | XMLStreamException ex) {
            throw new IllegalStateException("EPG 解析失败: " + sanitizeMessage(ex), ex);
        }

        int programCount = 0;
        int matchedChannelCount = 0;
        for (List<LiveProgramme> items : programmesByChannel.values()) {
            items.sort((left, right) -> Long.compare(left.start(), right.start()));
            programCount += items.size();
            if (!items.isEmpty()) {
                matchedChannelCount++;
            }
        }
        return new EpgParseResult(programmesByChannel, firstEpgIdByChannel, epgIconByChannel, programCount, matchedChannelCount);
    }

    private Set<String> matchChannels(String epgChannelId,
                                      List<String> displayNames,
                                      Map<String, List<String>> byTvgId,
                                      Map<String, List<String>> byName) {
        Set<String> matched = new LinkedHashSet<>();
        addMatches(matched, byTvgId.get(normalizeChannelKey(epgChannelId)));
        addMatches(matched, byName.get(normalizeChannelKey(epgChannelId)));
        for (String displayName : displayNames) {
            addMatches(matched, byTvgId.get(normalizeChannelKey(displayName)));
            addMatches(matched, byName.get(normalizeChannelKey(displayName)));
        }
        return matched;
    }

    private void addMatches(Set<String> target, List<String> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        target.addAll(matches);
    }

    private void index(Map<String, List<String>> index, String key, String channelId) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(channelId);
    }

    private long parseXmltvTime(String raw) {
        String value = safeText(raw);
        if (!StringUtils.hasText(value)) {
            return -1L;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        try {
            if (normalized.length() >= 20) {
                return OffsetDateTime.parse(normalized.substring(0, 20), XMLTV_DATE_TIME_WITH_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }
            if (normalized.length() >= 17) {
                return OffsetDateTime.parse(normalized.substring(0, 17), XMLTV_DATE_TIME_MINUTE_WITH_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }
        } catch (DateTimeParseException ignore) {
        }
        try {
            if (normalized.length() >= 14) {
                LocalDateTime dateTime = LocalDateTime.parse(normalized.substring(0, 14), XMLTV_DATE_TIME);
                return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            if (normalized.length() >= 12) {
                LocalDateTime dateTime = LocalDateTime.parse(normalized.substring(0, 12), XMLTV_DATE_TIME_MINUTE);
                return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
        } catch (DateTimeParseException ignore) {
        }
        return -1L;
    }

    private byte[] fetchBytes(String url, String label) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 TVBoxWeb Live/1.0")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "gzip")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(label + "拉取失败: status=" + response.statusCode());
            }
            return unwrapCompressed(response.body(), url, response.headers());
        } catch (Exception ex) {
            throw new IllegalStateException(label + "拉取失败: " + sanitizeMessage(ex), ex);
        }
    }

    private byte[] unwrapCompressed(byte[] body, String url, HttpHeaders headers) throws IOException {
        if (body == null || body.length == 0) {
            return new byte[0];
        }
        String contentEncoding = headers.firstValue("Content-Encoding").orElse("");
        boolean gzip = contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")
                || url.toLowerCase(Locale.ROOT).endsWith(".gz")
                || (body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B);
        if (!gzip) {
            return body;
        }
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzipInputStream.transferTo(output);
            return output.toByteArray();
        }
    }

    private String decodeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            Charset fallback = Charset.forName("GB18030");
            return new String(bytes, fallback);
        }
        return utf8;
    }

    private String normalizeExternalUrl(String rawUrl, String blankMessage) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException(blankMessage);
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }

    private String resolveAgainst(String baseUrl, String maybeRelative) {
        String value = safeText(maybeRelative);
        if (!StringUtils.hasText(value)) {
            return "";
        }
        try {
            URI base = URI.create(baseUrl);
            return base.resolve(value).toString();
        } catch (Exception ignore) {
            return value;
        }
    }

    private String maybeResolveAssetUrl(String baseUrl, String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("data:")) {
            return trimmed;
        }
        return resolveAgainst(baseUrl, trimmed);
    }

    private String deriveNameFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return uri.getHost();
            }
            Path fileName = Path.of(path).getFileName();
            return fileName == null ? path : fileName.toString();
        } catch (Exception ex) {
            return url;
        }
    }

    private String buildStableChannelId(String tvgId, String tvgName, String name, String group, String url) {
        String key = firstText(normalizeChannelKey(tvgId), normalizeChannelKey(tvgName), normalizeChannelKey(name));
        String seed = key + "|" + normalizeChannelKey(group) + "|" + safeText(url);
        return "live-" + Integer.toUnsignedString(seed.hashCode(), 36);
    }

    private String normalizeGroup(String group) {
        return firstText(safeText(group), "未分组");
    }

    private String normalizeChannelKey(String value) {
        String safe = safeText(value).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(safe)) {
            return "";
        }
        return safe.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+", "");
    }

    private int findUnquotedComma(String input) {
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '"') {
                quoted = !quoted;
            } else if (current == ',' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private boolean isProgrammeField(String localName) {
        return "title".equals(localName)
                || "sub-title".equals(localName)
                || "desc".equals(localName)
                || "category".equals(localName);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String limitText(String value, int maxLength) {
        String safe = safeText(value);
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String sanitizeMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return firstText(root.getMessage(), throwable.getMessage(), root.getClass().getSimpleName());
    }

    private record CacheEntry(Instant createdAt, Map<String, Object> payload) {
        boolean isExpired() {
            return createdAt.plus(CACHE_TTL).isBefore(Instant.now());
        }
    }

    private record PendingChannel(String tvgId, String title, String tvgName, String logo, String group) {
        PendingChannel withGroup(String nextGroup) {
            return new PendingChannel(tvgId, title, tvgName, logo, nextGroup);
        }
    }

    private record LiveChannel(String id,
                               String name,
                               String group,
                               String url,
                               String logo,
                               String tvgId,
                               String tvgName,
                               String epgChannelId,
                               List<LiveProgramme> programmes) {
        LiveChannel withEpg(String nextEpgChannelId, String nextLogo, List<LiveProgramme> nextProgrammes) {
            return new LiveChannel(
                    id,
                    name,
                    group,
                    url,
                    safe(nextLogo, logo),
                    tvgId,
                    tvgName,
                    safe(nextEpgChannelId, ""),
                    new ArrayList<>(nextProgrammes)
            );
        }

        private static String safe(String preferred, String fallback) {
            return StringUtils.hasText(preferred) ? preferred : fallback;
        }
    }

    private record LiveProgramme(long start, long end, String title, String subTitle, String desc, String category) {
    }

    private record EpgParseResult(Map<String, List<LiveProgramme>> programmesByChannel,
                                  Map<String, String> channelToEpgId,
                                  Map<String, String> epgIconByChannel,
                                  int programCount,
                                  int matchedChannelCount) {
    }

    private static final class MutableProgramme {
        private final String channelId;
        private final String start;
        private final String stop;
        private String title;
        private String subTitle;
        private String desc;
        private String category;

        private MutableProgramme(String channelId, String start, String stop) {
            this.channelId = channelId;
            this.start = start;
            this.stop = stop;
        }

        private void accept(String key, String value) {
            String safe = value == null ? "" : value.trim();
            if ("title".equals(key) && !safe.isEmpty()) {
                this.title = safe;
            } else if ("sub-title".equals(key) && !safe.isEmpty()) {
                this.subTitle = safe;
            } else if ("desc".equals(key) && !safe.isEmpty()) {
                this.desc = safe;
            } else if ("category".equals(key) && !safe.isEmpty()) {
                this.category = safe;
            }
        }
    }
}
