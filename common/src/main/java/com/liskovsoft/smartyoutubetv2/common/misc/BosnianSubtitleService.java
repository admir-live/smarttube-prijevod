package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.util.AtomicFile;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.ObservableEmitter;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Personal EN -> BS subtitles. Requests start only from the player's Translate action. */
public class BosnianSubtitleService {
    public static final String MODEL = "gpt-6-luna";
    private static final int CACHE_VERSION = 1;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    private volatile Call call;
    private volatile boolean cancelled;

    public static class Row {
        public int id;
        public long start;
        public long end;
        public String text;
        public String translation;

        Row(int id, long start, long end, String text) {
            this.id = id; this.start = start; this.end = end; this.text = text;
        }
    }

    public static class Translation {
        int version = CACHE_VERSION;
        String model = MODEL;
        public String videoId;
        public String title;
        public List<Row> rows = new ArrayList<>();
        Map<String, JsonObject> briefs = new LinkedHashMap<>();
        public long inputTokens;
        public long outputTokens;

        public int completed() {
            int count = 0;
            for (Row row : rows) if (row.translation != null && !row.translation.trim().isEmpty()) count++;
            return count;
        }

        public String textAt(long position) {
            int low = 0, high = rows.size() - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (rows.get(mid).start <= position) low = mid + 1;
                else high = mid - 1;
            }
            if (high < 0) return "";
            Row row = rows.get(high);
            return position < row.end && row.translation != null ? row.translation : "";
        }
    }

    public BosnianSubtitleService(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() {
        cancelled = true;
        Call active = call;
        if (active != null) active.cancel();
    }

    public void translate(String videoId, String apiKey, ObservableEmitter<Translation> emitter) throws Exception {
        emitter.setCancellable(this::cancel);
        checkCancelled();
        Translation result = load(context, videoId);
        if (result == null) {
            MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
            checkCancelled();
            if (info == null || info.isLive()) throw new IOException("Prijevod traži snimljeni video s engleskim transkriptom.");
            MediaSubtitle selected = selectEnglish(info.getSubtitles());
            if (selected == null) throw new IOException("Ovaj video nema dostupan engleski transkript.");
            HttpUrl source = HttpUrl.parse(selected.getBaseUrl());
            if (source == null || !source.isHttps() ||
                    !(source.host().equals("youtube.com") || source.host().endsWith(".youtube.com")))
                throw new IOException("Adresa YouTube transkripta nije prihvaćena.");
            HttpUrl url = source.newBuilder().removeAllQueryParameters("tlang").setQueryParameter("fmt", "json3").build();
            Request.Builder sourceRequest = new Request.Builder().url(url);
            if (info.getClientInfo() != null && info.getClientInfo().getUserAgent() != null)
                sourceRequest.header("User-Agent", info.getClientInfo().getUserAgent());
            JsonObject transcript = execute(sourceRequest.build(), false);
            result = new Translation();
            result.videoId = videoId; result.title = info.getTitle(); result.rows = parseTranscript(transcript);
            save(result);
        }
        emitter.onNext(snapshot(result));
        Map<Long, List<Row>> parts = new LinkedHashMap<>();
        for (Row row : result.rows) {
            long part = row.start / (30 * 60 * 1000L);
            if (!parts.containsKey(part)) parts.put(part, new ArrayList<>());
            parts.get(part).add(row);
        }
        JsonObject previous = null;
        for (Map.Entry<Long, List<Row>> part : parts.entrySet()) {
            checkCancelled();
            String partKey = String.valueOf(part.getKey());
            JsonObject brief = result.briefs.get(partKey);
            List<Row> missing = new ArrayList<>();
            for (Row row : part.getValue()) if (row.translation == null) missing.add(row);
            if (!missing.isEmpty()) {
                if (brief == null) {
                    JsonObject input = new JsonObject();
                    input.addProperty("video_title", result.title);
                    input.add("transcript_part", sourceRows(part.getValue(), false));
                    input.add("previous_context", previous);
                    JsonObject response = request(apiKey, requestBody(asset("bosnian_context.txt"), input, briefSchema(), 4000));
                    countUsage(result, response); save(result);
                    brief = readOutput(response);
                    validateBrief(brief, part.getValue());
                    result.briefs.put(partKey, brief); save(result);
                }
                for (List<Row> batch : batches(missing)) {
                    translateBatch(result, apiKey, batch, brief, emitter, 0);
                }
            }
            previous = brief;
        }
        emitter.onComplete();
    }

    public static MediaSubtitle selectEnglish(List<MediaSubtitle> tracks) {
        MediaSubtitle selected = null;
        if (tracks != null) for (MediaSubtitle track : tracks) {
            String language = track.getLanguageCode();
            HttpUrl url = HttpUrl.parse(track.getBaseUrl() == null ? "" : track.getBaseUrl());
            if (language != null && (language.equals("en") || language.startsWith("en-")) &&
                    url != null && url.queryParameter("tlang") == null) {
                if (selected == null || "asr".equals(selected.getType())) selected = track;
                if (!"asr".equals(track.getType())) break;
            }
        }
        return selected;
    }

    public static List<Row> parseTranscript(JsonObject json) throws IOException {
        if (!json.has("events") || !json.get("events").isJsonArray()) throw new IOException("YouTube nije vratio transkript.");
        List<Row> source = new ArrayList<>();
        JsonArray events = json.getAsJsonArray("events");
        if (events.size() > 15000) throw new IOException("Transkript je prevelik za ovu verziju.");
        for (JsonElement value : events) {
            JsonObject event = value.getAsJsonObject();
            if (!event.has("segs") || !event.has("tStartMs")) continue;
            long start = event.get("tStartMs").getAsLong();
            long duration = event.has("dDurationMs") ? event.get("dDurationMs").getAsLong() : 4000;
            StringBuilder text = new StringBuilder();
            for (JsonElement seg : event.getAsJsonArray("segs")) {
                JsonObject item = seg.getAsJsonObject();
                if (item.has("utf8")) text.append(item.get("utf8").getAsString());
            }
            String clean = text.toString().replaceAll("\\s+", " ").trim();
            if (start >= 0 && start <= 7 * 24 * 3600000L && duration > 0 && !clean.isEmpty())
                source.add(new Row(0, start, start + Math.min(duration, 60000), clean));
        }
        Collections.sort(source, (left, right) -> Long.compare(left.start, right.start));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            Row row = source.get(i);
            long end = i + 1 < source.size() ? Math.min(row.end, source.get(i + 1).start) : row.end;
            if (end <= row.start) continue;
            // shortcut: sparse caption timestamps use proportional timing; add word timing if TV testing shows drift.
            int pieces = Math.max((int) Math.ceil(row.text.length() / 72.0), (int) Math.ceil((end - row.start) / 5000.0));
            String[] words = row.text.split(" ");
            pieces = Math.min(pieces, words.length);
            int at = 0;
            for (int piece = 0; piece < pieces; piece++) {
                int through = (int) Math.round((piece + 1) * words.length / (double) pieces);
                StringBuilder text = new StringBuilder();
                for (int word = at; word < through; word++) {
                    if (word > at) text.append(' ');
                    text.append(words[word]);
                }
                long begin = row.start + (end - row.start) * at / words.length;
                long finish = row.start + (end - row.start) * through / words.length;
                if (finish > begin) rows.add(new Row(rows.size(), begin, finish, text.toString()));
                at = through;
            }
        }
        if (rows.isEmpty() || rows.size() > 15000) throw new IOException("Nema upotrebljivog engleskog transkripta ili je prevelik.");
        return rows;
    }

    private void translateBatch(Translation result, String key, List<Row> batch, JsonObject brief,
                                ObservableEmitter<Translation> emitter, int depth) throws Exception {
        checkCancelled();
        JsonObject input = new JsonObject();
        input.addProperty("video_title", result.title);
        input.add("video_brief", brief);
        int first = batch.get(0).id, last = batch.get(batch.size() - 1).id;
        input.add("context_before", sourceRows(result.rows.subList(Math.max(0, first - 5), first), true));
        input.add("context_after", sourceRows(result.rows.subList(last + 1, Math.min(result.rows.size(), last + 6)), false));
        input.add("segments", sourceRows(batch, false));
        JsonObject response = request(key, requestBody(asset("bosnian_translation.txt"), input, translationSchema(batch), 10000));
        countUsage(result, response); save(result);
        JsonObject output;
        if ("incomplete".equals(string(response, "status")) && response.has("incomplete_details") &&
                "max_output_tokens".equals(string(response.getAsJsonObject("incomplete_details"), "reason"))) output = new JsonObject();
        else output = readOutput(response);
        JsonObject translations = output.has("translations") && output.get("translations").isJsonObject() ?
                output.getAsJsonObject("translations") : new JsonObject();
        List<Row> missing = new ArrayList<>();
        for (Row row : batch) {
            JsonElement text = translations.get(String.valueOf(row.id));
            if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString() &&
                    !text.getAsString().trim().isEmpty() && text.getAsString().length() <= 2000)
                row.translation = text.getAsString().replaceAll("\\s+", " ").trim();
            else missing.add(row);
        }
        save(result); emitter.onNext(snapshot(result));
        if (!missing.isEmpty()) {
            if (depth >= 2) throw new IOException("Neki titlovi nisu prevedeni. Sačuvano je završeno; izaberi Nastavi prijevod.");
            int size = Math.max(1, missing.size() / 2);
            for (int i = 0; i < missing.size(); i += size)
                translateBatch(result, key, missing.subList(i, Math.min(i + size, missing.size())), brief, emitter, depth + 1);
        }
    }

    public static List<List<Row>> batches(List<Row> rows) {
        List<List<Row>> batches = new ArrayList<>();
        List<Row> batch = new ArrayList<>(); int chars = 0;
        for (Row row : rows) {
            if (!batch.isEmpty() && (batch.size() == 32 || chars + row.text.length() > 4000)) {
                batches.add(batch); batch = new ArrayList<>(); chars = 0;
            }
            batch.add(row); chars += row.text.length();
        }
        if (!batch.isEmpty()) batches.add(batch);
        return batches;
    }

    private static JsonArray sourceRows(List<Row> rows, boolean includeTranslation) {
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("id", row.id); item.addProperty("text", row.text);
            double duration = (row.end - row.start) / 1000.0;
            item.addProperty("duration_seconds", duration);
            item.addProperty("preferred_max_characters", Math.min(72, Math.max(20, (int) (duration * 17))));
            if (includeTranslation) item.addProperty("translation", row.translation);
            array.add(item);
        }
        return array;
    }

    private static JsonObject objectSchema(JsonObject properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        for (String name : properties.keySet()) required.add(name);
        schema.add("required", required);
        return schema;
    }

    public static JsonObject translationSchema(List<Row> rows) {
        JsonObject properties = new JsonObject();
        for (Row row : rows) properties.add(String.valueOf(row.id), new JsonParser().parse("{\"type\":\"string\"}"));
        JsonObject outer = new JsonObject(); outer.add("translations", objectSchema(properties));
        return objectSchema(outer);
    }

    private JsonObject briefSchema() throws IOException {
        return new JsonParser().parse(asset("bosnian_context_schema.json")).getAsJsonObject();
    }

    private static void validateBrief(JsonObject brief, List<Row> rows) throws IOException {
        if (string(brief, "summary").isEmpty() || !brief.has("sections") || !brief.get("sections").isJsonArray() ||
                !brief.has("glossary") || !brief.get("glossary").isJsonArray())
            throw new IOException("Priprema konteksta nije uspjela. Pokušaj ponovo.");
        brief.addProperty("summary", string(brief, "summary").substring(0, Math.min(800, string(brief, "summary").length())));
        if (brief.getAsJsonArray("sections").size() > 8 || brief.getAsJsonArray("glossary").size() > 24)
            throw new IOException("Priprema je vratila prevelik kontekst. Pokušaj ponovo.");
        int first = rows.get(0).id, last = rows.get(rows.size() - 1).id;
        for (JsonElement section : brief.getAsJsonArray("sections")) {
            JsonObject item = section.getAsJsonObject();
            int from = item.get("from_id").getAsInt(), to = item.get("to_id").getAsInt();
            if (from < first || to > last || from > to) throw new IOException("Priprema je vratila pogrešne oznake titlova.");
        }
    }

    public static JsonObject requestBody(String instructions, JsonObject input, JsonObject schema, int maxOutput) {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL); body.addProperty("store", false);
        body.addProperty("instructions", instructions); body.addProperty("input", input.toString());
        body.addProperty("max_output_tokens", maxOutput);
        body.add("reasoning", new JsonParser().parse("{\"effort\":\"none\"}"));
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema"); format.addProperty("name", "bosnian_subtitles");
        format.addProperty("strict", true); format.add("schema", schema);
        JsonObject text = new JsonObject(); text.add("format", format); body.add("text", text);
        return body;
    }

    private JsonObject request(String key, JsonObject body) throws Exception {
        Request request = new Request.Builder().url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString())).build();
        for (int attempt = 0; ; attempt++) {
            try { return execute(request, true); }
            catch (RetryableException e) {
                if (attempt == 2) throw new IOException("OpenAI je privremeno zauzet. Nastavi prijevod kasnije.");
                Thread.sleep(1000L << attempt); checkCancelled();
            }
        }
    }

    private JsonObject execute(Request request, boolean api) throws IOException {
        checkCancelled(); call = client.newCall(request);
        if (cancelled) call.cancel();
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                if (api && (response.code() == 429 || response.code() >= 500)) {
                    String error = response.body() == null ? "" : readLimited(response.body().byteStream());
                    if (error.contains("insufficient_quota")) throw new IOException("OpenAI račun nema kredita ili je dosegao limit.");
                    throw new RetryableException();
                }
                if (api && response.code() == 401) throw new IOException("API ključ nije prihvaćen. Unesi ispravan ključ.");
                if (api && (response.code() == 403 || response.code() == 404))
                    throw new IOException("GPT-6 Luna nije dostupan ovom API ključu.");
                throw new IOException(api ? "OpenAI zahtjev nije uspio (HTTP " + response.code() + ")." :
                        "YouTube nije dozvolio preuzimanje transkripta (HTTP " + response.code() + ").");
            }
            if (response.body() == null) throw new IOException("Servis je vratio prazan odgovor.");
            try { return new JsonParser().parse(readLimited(response.body().byteStream())).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Servis nije vratio očekivani JSON odgovor."); }
        } finally { call = null; }
    }

    public static JsonObject readOutput(JsonObject response) throws IOException {
        if (!"completed".equals(string(response, "status"))) throw new IOException("OpenAI nije završio odgovor. Nastavi prijevod.");
        StringBuilder text = new StringBuilder();
        if (response.has("output")) for (JsonElement item : response.getAsJsonArray("output")) {
            JsonObject object = item.getAsJsonObject();
            if (!object.has("content")) continue;
            for (JsonElement value : object.getAsJsonArray("content")) {
                JsonObject content = value.getAsJsonObject();
                if ("refusal".equals(string(content, "type"))) throw new IOException("Model je odbio ovaj transkript.");
                if ("output_text".equals(string(content, "type"))) text.append(string(content, "text"));
            }
        }
        try { return new JsonParser().parse(text.toString()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("OpenAI je vratio neispravan prijevod. Nastavi prijevod."); }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static void countUsage(Translation result, JsonObject response) {
        if (response.has("usage") && response.get("usage").isJsonObject()) {
            JsonObject usage = response.getAsJsonObject("usage");
            if (usage.has("input_tokens")) result.inputTokens += usage.get("input_tokens").getAsLong();
            if (usage.has("output_tokens")) result.outputTokens += usage.get("output_tokens").getAsLong();
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Prijevod je zaustavljen.");
    }

    private String asset(String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) { return readLimited(input); }
    }

    private static String readLimited(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int size;
        while ((size = input.read(buffer)) != -1) {
            if (out.size() + size > MAX_BYTES) throw new IOException("Odgovor je prevelik.");
            out.write(buffer, 0, size);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static File cacheFile(Context context, String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid video ID");
        File directory = new File(context.getNoBackupFilesDir(), "bosnian-subtitles");
        return new File(directory, videoId + ".json");
    }

    public static Translation load(Context context, String videoId) {
        try (InputStream input = new AtomicFile(cacheFile(context, videoId)).openRead()) {
            Translation result = GSON.fromJson(readLimited(input), Translation.class);
            if (result == null || result.version != CACHE_VERSION || !MODEL.equals(result.model) ||
                    !videoId.equals(result.videoId) || result.rows == null || result.rows.isEmpty() ||
                    result.rows.size() > 15000 || result.briefs == null) return null;
            long end = 0;
            for (int i = 0; i < result.rows.size(); i++) {
                Row row = result.rows.get(i);
                if (row == null || row.id != i || row.start < end || row.end <= row.start || row.text == null || row.text.isEmpty()) return null;
                end = row.end;
            }
            return result;
        } catch (IOException | RuntimeException e) { return null; }
    }

    private void save(Translation result) throws IOException {
        File file = cacheFile(context, result.videoId);
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("Nema mjesta za čuvanje prijevoda.");
        byte[] bytes = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Prijevod je prevelik za lokalno čuvanje.");
        AtomicFile atomic = new AtomicFile(file); FileOutputStream stream = null;
        try {
            stream = atomic.startWrite(); stream.write(bytes); atomic.finishWrite(stream);
        } catch (IOException e) { atomic.failWrite(stream); throw new IOException("Prijevod nije sačuvan. Provjeri slobodan prostor."); }
        File[] files = file.getParentFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        long total = 0; for (File cached : files) total += cached.length();
        for (File cached : files) {
            if (total <= 30 * 1024 * 1024) break;
            if (!cached.equals(file)) { long size = cached.length(); if (cached.delete()) total -= size; }
        }
    }

    private static Translation snapshot(Translation result) {
        // Worker keeps translating; the UI receives its own rows.
        return GSON.fromJson(GSON.toJson(result), Translation.class);
    }

    private static class RetryableException extends IOException {}
}
