package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.BosnianSubtitleService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import io.reactivex.android.plugins.RxAndroidPlugins;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BosnianSubtitleControllerTest {
    private Host controller;
    private String displayed;
    private int subtitleWrites;
    private long position;

    private class Host extends BosnianSubtitleController {
        private final PlaybackView player = (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class[]{PlaybackView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPositionMs")) return position;
                    if (method.getName().equals("getSubtitleFormat")) return FormatItem.SUBTITLE_NONE;
                    if (method.getName().equals("setTranslatedSubtitle")) { displayed = (String) args[0]; subtitleWrites++; }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 0F;
                    return null;
                });
        @Override public PlaybackView getPlayer() { return player; }
        @Override public Context getContext() { return RuntimeEnvironment.getApplication(); }
        @Override protected boolean isEmbedPlayer() { return false; }
    }

    @Before public void setUp() throws Exception {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        position = 1200;
        controller = new Host();
        writeCache("{\"version\":1,\"model\":\"gpt-6-luna\",\"videoId\":\"abcdefghijk\",\"title\":\"Example\"," +
                "\"briefs\":{},\"rows\":[{\"id\":0,\"start\":1000,\"end\":2000,\"text\":\"Hello\",\"translation\":\"Zdravo\"}]}");
    }

    @After public void tearDown() {
        controller.onFinish();
        RxJavaPlugins.reset(); RxAndroidPlugins.reset();
    }

    @Test public void cachedTranslationLoadsWithoutApiAndVideoChangeClearsIt() {
        controller.onNewVideo(Video.from("abcdefghijk"));
        assertTrue(controller.isEnabled()); assertEquals("Zdravo", displayed);
        controller.onNewVideo(Video.from("lmnopqrstuv"));
        assertFalse(controller.isEnabled()); assertNull(displayed);
    }

    @Test public void buildKeyIsAvailableBeforeInputAndSurvivesClosingPlayer() throws Exception {
        java.lang.reflect.Field field = BosnianSubtitleController.class.getDeclaredField("apiKey");
        field.setAccessible(true);
        String initial = (String) field.get(null);
        assertTrue("Build configuration must provide the key before user input",
                BuildConfig.BOSNIAN_API_KEY.isEmpty() ? initial == null : BuildConfig.BOSNIAN_API_KEY.equals(initial));
        controller.onFinish();
        controller = new Host();
        assertTrue("Closing and reopening the player must preserve the configured key",
                java.util.Objects.equals(initial, field.get(null)));
    }

    @Test public void nativeSubtitleChoiceTurnsOffBosnianDisplay() {
        controller.onNewVideo(Video.from("abcdefghijk"));
        FormatItem nativeTrack = (FormatItem) Proxy.newProxyInstance(FormatItem.class.getClassLoader(),
                new Class[]{FormatItem.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getType")) return FormatItem.TYPE_SUBTITLE;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        controller.onTrackSelected(nativeTrack);
        assertFalse(controller.isEnabled()); assertNull(displayed);
    }

    @Test public void closingPlayerStopsSubtitleCallbacks() {
        controller.onNewVideo(Video.from("abcdefghijk"));
        controller.onViewDestroyed();
        int writes = subtitleWrites;
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertEquals(writes, subtitleWrites); assertFalse(controller.isEnabled());
    }

    @Test public void incompatibleOrMalformedCacheIsIgnored() throws Exception {
        writeCache("{\"version\":99,\"model\":\"gpt-6-luna\",\"videoId\":\"abcdefghijk\",\"rows\":[]}");
        assertNull(BosnianSubtitleService.load(controller.getContext(), "abcdefghijk"));
        writeCache("broken JSON");
        assertNull(BosnianSubtitleService.load(controller.getContext(), "abcdefghijk"));
        assertNull(BosnianSubtitleService.load(controller.getContext(), "../../escape"));
    }

    private void writeCache(String content) throws Exception {
        File folder = new File(RuntimeEnvironment.getApplication().getNoBackupFilesDir(), "bosnian-subtitles");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Cannot create test cache");
        try (FileOutputStream out = new FileOutputStream(new File(folder, "abcdefghijk.json"))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
