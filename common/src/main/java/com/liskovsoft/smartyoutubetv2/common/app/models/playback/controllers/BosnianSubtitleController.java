package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.BosnianSubtitleService;
import com.liskovsoft.smartyoutubetv2.common.misc.BosnianSubtitleService.Translation;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class BosnianSubtitleController extends BasePlayerController {
    private static String apiKey = BuildConfig.BOSNIAN_API_KEY.isEmpty() ? null : BuildConfig.BOSNIAN_API_KEY;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Disposable action;
    private Translation translation;
    private boolean enabled;
    private boolean displayRequested;
    private boolean translating;
    private int generation;
    private FormatItem originalFormat;
    private final Runnable display = new Runnable() {
        @Override public void run() {
            if (!enabled || getPlayer() == null || translation == null) return;
            getPlayer().setTranslatedSubtitle(translation.textAt(getPlayer().getPositionMs()));
            getPlayer().setButtonState(R.id.lb_control_closed_captioning, PlayerUI.BUTTON_ON);
            handler.postDelayed(this, 100);
        }
    };

    public boolean isEnabled() { return enabled; }

    public void appendMenu(AppDialogPresenter dialog) {
        if (isEmbedPlayer()) return;
        dialog.appendSingleButton(UiOptionItem.from("Naš prijevod — bosanski titlovi", item -> showMenu()));
    }

    private void showMenu() {
        AppDialogPresenter dialog = getAppDialogPresenter();
        if (translation != null) {
            dialog.appendSingleButton(UiOptionItem.from("Prevedeno " + translation.completed() + "/" + translation.rows.size() +
                    " titlova · tokeni " + (translation.inputTokens + translation.outputTokens), item -> {}));
        }
        if (translating) {
            dialog.appendSingleButton(UiOptionItem.from("Zaustavi prijevod", item -> {
                stop(); showMessage("Prijevod zaustavljen. Završeni titlovi su sačuvani."); dialog.closeDialog();
            }));
        } else if (translation == null || translation.completed() < translation.rows.size()) {
            dialog.appendSingleButton(UiOptionItem.from(translation == null ? "Prevedi video" : "Nastavi prijevod", item -> {
                dialog.closeDialog();
                if (apiKey == null) enterKey(true); else start();
            }));
        }
        if (translation != null && translation.completed() > 0) {
            dialog.appendSingleButton(UiOptionItem.from(enabled ? "Isključi bosanske titlove" : "Prikaži bosanske titlove", item -> {
                if (enabled) disable(); else enable();
                dialog.closeDialog();
            }));
        }
        if (BuildConfig.BOSNIAN_API_KEY.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from("Unesi OpenAI API ključ", item -> {
                dialog.closeDialog(); enterKey(false);
            }));
            if (apiKey != null) dialog.appendSingleButton(UiOptionItem.from("Ukloni API ključ", item -> {
                stop(); apiKey = null; dialog.closeDialog(); showMessage("API ključ uklonjen.");
            }));
        } else {
            dialog.appendSingleButton(UiOptionItem.from("API ključ je unaprijed podešen.", item -> {}));
        }
        dialog.appendSingleButton(UiOptionItem.from("Prijevod šalje transkript OpenAI-ju. API potrošnja se naplaćuje.", item -> {}));
        dialog.showDialog("Naš prijevod");
    }

    private void enterKey(boolean translateAfter) {
        final int expected = generation;
        SimpleEditDialog.showPassword(getContext(), "OpenAI API ključ (pamti se do zatvaranja aplikacije)", null, value -> {
            String key = value.trim();
            if (!key.startsWith("sk-") || key.matches(".*\\s.*") || key.length() < 20 || key.length() > 512) {
                showMessage("Unesi ispravan OpenAI API ključ."); return false;
            }
            apiKey = key;
            if (translateAfter && expected == generation) start();
            return true;
        });
    }

    private void start() {
        Video video = getVideo();
        if (video == null || video.videoId == null || getPlayer() == null) return;
        if (video.isLive) { showMessage("Prijenosi uživo nisu podržani."); return; }
        stop();
        final int expected = generation;
        final String key = apiKey;
        BosnianSubtitleService service = new BosnianSubtitleService(getContext());
        translating = true;
        displayRequested = true;
        showMessage("Učitavam transkript i pripremam prijevod. Meni možeš zatvoriti.");
        action = Observable.<Translation>create(emitter -> service.translate(video.videoId, key, emitter))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (expected != generation) return;
                    translation = result;
                    if (displayRequested && result.completed() > 0 && !enabled) enable();
                }, error -> {
                    if (expected != generation) return;
                    translating = false;
                    showMessage(error instanceof java.io.IOException ? error.getMessage() : "Prijevod nije uspio. Pokušaj nastaviti.");
                }, () -> {
                    if (expected != generation) return;
                    translating = false;
                    showMessage("Bosanski prijevod je spreman.");
                });
    }

    private void enable() {
        if (getPlayer() == null || translation == null) return;
        if (!enabled) originalFormat = getPlayer().getSubtitleFormat();
        enabled = true;
        displayRequested = true;
        getPlayer().setFormat(FormatItem.SUBTITLE_NONE);
        getPlayer().showSubtitles(true);
        handler.removeCallbacks(display); display.run();
    }

    public void disable() {
        enabled = false; displayRequested = false; handler.removeCallbacks(display);
        if (getPlayer() != null) {
            getPlayer().setTranslatedSubtitle(null);
            getPlayer().setButtonState(R.id.lb_control_closed_captioning, PlayerUI.BUTTON_OFF);
            if (originalFormat != null) getPlayer().setFormat(originalFormat);
        }
        originalFormat = null;
    }

    private void stop() {
        generation++;
        if (action != null) { action.dispose(); action = null; }
        translating = false;
    }

    @Override public void onNewVideo(Video video) {
        stop(); disable(); translation = null;
        if (isEmbedPlayer() || video.videoId == null) return;
        final int expected = generation;
        action = Observable.fromCallable(() -> {
            Translation cached = BosnianSubtitleService.load(getContext(), video.videoId);
            return cached == null ? new Translation() : cached;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(cached -> {
            if (expected == generation && video.videoId.equals(cached.videoId)) {
                translation = cached;
                if (cached.completed() > 0) enable();
            }
        }, error -> {});
    }

    @Override public void onVideoLoaded(Video video) { if (enabled) enable(); }
    @Override public void onTrackSelected(FormatItem track) {
        if (displayRequested && track != null && track.getType() == FormatItem.TYPE_SUBTITLE && !track.isDefault()) disable();
    }
    @Override public void onEngineReleased() { stop(); handler.removeCallbacks(display); }
    @Override public void onViewDestroyed() { stop(); disable(); }
    @Override public void onViewResumed() { if (enabled) { handler.removeCallbacks(display); display.run(); } }
    @Override public void onFinish() { stop(); disable(); translation = null; }

    private void showMessage(String message) { MessageHelpers.showMessage(getContext(), message); }
}
