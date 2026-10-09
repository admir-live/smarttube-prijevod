package com.liskovsoft.smartyoutubetv2.common.misc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.misc.BosnianSubtitleService.Row;
import com.liskovsoft.smartyoutubetv2.common.misc.BosnianSubtitleService.Translation;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BosnianSubtitleServiceTest {
    private JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }

    @Test public void transcriptKeepsGapsAndClipsOverlap() throws Exception {
        List<Row> rows = BosnianSubtitleService.parseTranscript(json("{\"events\":[" +
                "{\"tStartMs\":1000,\"dDurationMs\":5000,\"segs\":[{\"utf8\":\"Hello \"},{\"utf8\":\"world\"}]}," +
                "{\"tStartMs\":3000,\"dDurationMs\":1000,\"segs\":[{\"utf8\":\"Second\"}]}," +
                "{\"tStartMs\":8000,\"dDurationMs\":1000,\"segs\":[{\"utf8\":\"Last\"}]}," +
                "{\"tStartMs\":9000,\"segs\":[{\"utf8\":\"\\n\"}]}]}"));
        assertEquals(3, rows.size()); assertEquals("Hello world", rows.get(0).text);
        assertEquals(3000, rows.get(0).end);
        rows.get(0).translation = "Zdravo"; rows.get(1).translation = "Drugo";
        Translation translated = new Translation(); translated.rows = rows;
        assertEquals("", translated.textAt(999)); assertEquals("Zdravo", translated.textAt(1000));
        assertEquals("Drugo", translated.textAt(3000)); assertEquals("", translated.textAt(4000));
        assertEquals("", translated.textAt(8000)); // Untranslated text must never display as Bosnian.
        assertEquals("Zdravo", translated.textAt(2000)); // Seeking backwards.
        assertEquals(2, translated.completed());
    }

    @Test public void longCaptionSplitsWithoutLosingWordsOrTiming() throws Exception {
        List<Row> rows = BosnianSubtitleService.parseTranscript(json("{\"events\":[" +
                "{\"tStartMs\":2000,\"dDurationMs\":12000,\"segs\":[{\"utf8\":\"one two three four five six\"}]}]}"));
        assertEquals(3, rows.size()); assertEquals("one two", rows.get(0).text);
        assertEquals("three four", rows.get(1).text); assertEquals("five six", rows.get(2).text);
        assertEquals(2000, rows.get(0).start); assertEquals(14000, rows.get(2).end);
        assertEquals(rows.get(0).end, rows.get(1).start);
    }

    @Test(expected = IOException.class) public void emptyTranscriptIsRejected() throws Exception {
        BosnianSubtitleService.parseTranscript(json("{\"events\":[]}"));
    }

    @Test public void englishSelectionPrefersAuthorAndRejectsYoutubeMachineTranslation() {
        MediaSubtitle auto = track("en", "asr", null), author = track("en-US", null, null);
        assertSame(author, BosnianSubtitleService.selectEnglish(Arrays.asList(track("fr", null, null), auto, author)));
        assertSame(auto, BosnianSubtitleService.selectEnglish(Arrays.asList(auto)));
        assertNull(BosnianSubtitleService.selectEnglish(Arrays.asList(track("en", "asr", "en"))));
    }

    @Test public void batchSizeAndRequiredIdsStayBounded() {
        Row row = new Row(42, 0, 1000, "Hello");
        Row[] source = new Row[65]; Arrays.fill(source, row);
        List<List<Row>> batches = BosnianSubtitleService.batches(Arrays.asList(source));
        assertEquals(3, batches.size()); assertEquals(32, batches.get(0).size());
        JsonObject request = BosnianSubtitleService.requestBody("Translate", new JsonObject(),
                BosnianSubtitleService.translationSchema(Arrays.asList(row)), 10000);
        assertFalse(request.get("store").getAsBoolean());
        assertEquals("gpt-6-luna", request.get("model").getAsString());
        JsonObject properties = request.getAsJsonObject("text").getAsJsonObject("format").getAsJsonObject("schema")
                .getAsJsonObject("properties").getAsJsonObject("translations");
        assertEquals("42", properties.getAsJsonArray("required").get(0).getAsString());
    }

    @Test public void responseParsesMessageInsteadOfReasoning() throws Exception {
        JsonObject response = json("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"}," +
                "{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"translations\\\":{\\\"42\\\":\\\"Zdravo\\\"}}\"}]}]}");
        assertEquals("Zdravo", BosnianSubtitleService.readOutput(response).getAsJsonObject("translations").get("42").getAsString());
    }

    @Test(expected = IOException.class) public void refusalIsNotAcceptedAsTranslation() throws Exception {
        BosnianSubtitleService.readOutput(json("{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}"));
    }

    @Test(expected = IOException.class) public void incompleteResponseIsRejected() throws Exception {
        BosnianSubtitleService.readOutput(json("{\"status\":\"incomplete\",\"output\":[]}"));
    }

    private MediaSubtitle track(String lang, String kind, String translated) {
        return new MediaSubtitle() {
            public String getBaseUrl() { return "https://www.youtube.com/api/timedtext?v=abcdefghijk" + (translated == null ? "" : "&tlang=" + translated); }
            public String getLanguageCode() { return lang; }
            public String getType() { return kind; }
            public String getVssId() { return null; } public String getName() { return lang; }
            public boolean isTranslatable() { return true; }
            public String getMimeType() { return null; } public String getCodecs() { return null; }
        };
    }
}
