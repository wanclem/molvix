package com.molvix.android.ui.widgets;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.devbrackets.android.exomedia.listener.VideoControlsButtonListener;
import com.devbrackets.android.exomedia.listener.VideoControlsVisibilityListener;
import com.devbrackets.android.exomedia.ui.animation.TopViewHideShowAnimation;
import com.devbrackets.android.exomedia.ui.widget.VideoControls;
import com.devbrackets.android.exomedia.ui.widget.VideoView;
import com.molvix.android.R;
import com.molvix.android.beans.DownloadedVideoItem;
import com.molvix.android.eventbuses.RefreshTheme;
import com.molvix.android.managers.ThemeManager;
import com.molvix.android.preferences.AppPrefs;
import com.molvix.android.ui.activities.MainActivity;
import com.molvix.android.utils.MolvixLogger;
import com.molvix.android.utils.UiUtils;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import butterknife.BindView;
import butterknife.ButterKnife;

public class MolvixVideoPlayerView extends FrameLayout {

    @BindView(R.id.video_title_view)
    TextView videoTitleView;

    @BindView(R.id.video_nav_back)
    ImageView videoNavBackView;

    @BindView(R.id.video_view)
    VideoView videoView;

    @BindView(R.id.title_view_container)
    View titleViewContainer;

    private AtomicReference<File> activeFileReference = new AtomicReference<>();
    private AtomicInteger currentOrientation = new AtomicInteger(1);
    private AtomicBoolean controlsShown = new AtomicBoolean(false);
    private AtomicBoolean titleContainerVisible = new AtomicBoolean(true);
    private AtomicLong totalVideoDurationRef = new AtomicLong(0);
    private ThemeManager.ThemeSelection initialThemeSelection;

    public MolvixVideoPlayerView(@NonNull Context context) {
        super(context);
        initUI(context);
    }

    public MolvixVideoPlayerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initUI(context);
    }

    public MolvixVideoPlayerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initUI(context);
    }

    private void initUI(Context context) {
        removeAllViews();
        @SuppressLint("InflateParams") View playerView = LayoutInflater.from(context).inflate(R.layout.video_player_view, null);
        ButterKnife.bind(this, playerView);
        addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void playVideos(List<DownloadedVideoItem> playList, int startIndex) {
        try {
            initDeviceOrientation();
            DownloadedVideoItem activeItemOnPlayList = playList.get(startIndex);
            setupVideoControls(playList, startIndex);
            videoTitleView.setText(activeItemOnPlayList.getTitle());
            videoView.setVideoPath(activeItemOnPlayList.getDownloadedFile().getPath());
            videoView.setOnPreparedListener(() -> {
                try {
                    long lastPlayBackPosition = AppPrefs.getLastMediaPlayBackPositionFor(activeItemOnPlayList.getDownloadedFile());
                    if (lastPlayBackPosition > 0) {
                        videoView.seekTo(lastPlayBackPosition);
                    }
                    videoView.start();
                    activeFileReference.set(activeItemOnPlayList.getDownloadedFile());
                    long totalVideoDuration = videoView.getDuration();
                    totalVideoDurationRef.set(totalVideoDuration);
                } catch (Exception ignored) {

                }
            });
            videoView.setOnBufferUpdateListener(percent -> {
                long totalVideoDuration = totalVideoDurationRef.get();
                if (totalVideoDuration > 0) {
                    long currentVideoDuration = videoView.getCurrentPosition();
                    if (currentVideoDuration > totalVideoDuration) {
                        tryPlayNextEpisode(playList, startIndex);
                    }
                }
            });
            videoView.setOnErrorListener(e -> {
                AlertDialog.Builder errorBuilder = new AlertDialog.Builder(getContext());
                errorBuilder.setMessage(UiUtils.fromHtml("Sorry, couldn't play <b>" + activeItemOnPlayList.getTitle() + "</b>.It seems this video is corrupt or not fully downloaded."));
                errorBuilder.setPositiveButton("OK", (dialog, which) -> {
                    dialog.dismiss();
                    removePlayer();
                });
                errorBuilder.create().show();
                return true;
            });
            videoNavBackView.setOnClickListener(v -> {
                UiUtils.blinkView(videoNavBackView);
                removePlayer();
            });
            videoTitleView.setOnClickListener(v -> {
                //Do nothing, just don't let the search box behind take focus
            });
        } catch (Exception ignored) {

        }
    }

    private void initDeviceOrientation() {
        if (getContext() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getContext();
            int orientation = mainActivity.getResources().getConfiguration().orientation;
            currentOrientation.set(orientation);
        }
    }

    private void removePlayer() {
        cleanUpVideoView();
        ((ViewGroup) getParent()).removeView(MolvixVideoPlayerView.this);
    }

    @SuppressWarnings("deprecation")
    private void setupVideoControls(List<DownloadedVideoItem> downloadedVideoItems,
                                    int startIndex) {
        VideoControls videoControls = videoView.getVideoControls();
        if (videoControls != null) {
            tryShowNextButton(downloadedVideoItems, startIndex, videoControls);
            tryShowPreviousButton(downloadedVideoItems, startIndex, videoControls);
            listenToControlButtonClicks(downloadedVideoItems, startIndex, videoControls);
            listenToControlsVisibilityChange(videoControls);
            videoView.setOnCompletionListener(() -> {
                AppPrefs.persistMediaPlayBackPositionFor(downloadedVideoItems.get(startIndex).getDownloadedFile(), 0);
                int nextIndex = startIndex + 1;
                try {
                    DownloadedVideoItem nextOnList = downloadedVideoItems.get(nextIndex);
                    if (nextOnList != null) {
                        playVideos(downloadedVideoItems, downloadedVideoItems.indexOf(nextOnList));
                    } else {
                        removePlayer();
                    }
                } catch (Exception ignored) {
                    removePlayer();
                }
            });
            setOnSystemUiVisibilityChangeListener(visibility -> {
                if (visibility == View.SYSTEM_UI_FLAG_VISIBLE && currentOrientation.get() == Configuration.ORIENTATION_LANDSCAPE) {
                    if (!controlsShown.get()) {
                        reEnterImmersiveModeDelayed();
                    }
                }
            });
        }
    }

    private void reEnterImmersiveModeDelayed() {
        new Handler().postDelayed(this::enterImmersiveMode, 3000);
    }

    private void listenToControlsVisibilityChange(VideoControls videoControls) {
        videoControls.setVisibilityListener(new VideoControlsVisibilityListener() {

            @Override
            public void onControlsShown() {
                animateTitleContainerVisibility(true);
                controlsShown.set(true);
            }

            @Override
            public void onControlsHidden() {
                animateTitleContainerVisibility(false);
                if (currentOrientation.get() == Configuration.ORIENTATION_LANDSCAPE) {
                    enterImmersiveMode();
                }
                controlsShown.set(false);
            }
        });
    }

    private void animateTitleContainerVisibility(boolean toVisible) {
        if (titleContainerVisible.get() == toVisible) {
            return;
        }
        titleViewContainer.startAnimation(new TopViewHideShowAnimation(titleViewContainer, toVisible, 300L));
        titleContainerVisible.set(toVisible);
    }

    private void leaveImmersiveMode() {
        if (getContext() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getContext();
            View decorView = mainActivity.getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void enterImmersiveMode() {
        if (getContext() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getContext();
            View decorView = mainActivity.getWindow().getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE
                                // Set the content to appear under the system bars so that the
                                // content doesn't resize when the system bars hide and show.
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                // Hide the nav bar and status bar
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        initialThemeSelection = ThemeManager.getThemeSelection();
        ThemeManager.setThemeSelection(ThemeManager.ThemeSelection.DARK);
        EventBus.getDefault().post(new RefreshTheme());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        leaveImmersiveMode();
        if (initialThemeSelection != null) {
            ThemeManager.setThemeSelection(initialThemeSelection);
            EventBus.getDefault().post(new RefreshTheme());
        }
    }

    private void listenToControlButtonClicks(List<DownloadedVideoItem> downloadedVideoItems,
                                             int startIndex, VideoControls videoControls) {
        videoControls.setButtonListener(new VideoControlsButtonListener() {
            @Override
            public boolean onPlayPauseClicked() {
                return false;
            }

            @Override
            public boolean onPreviousClicked() {
                tryPlayPreviousEpisode(downloadedVideoItems, startIndex);
                return true;
            }

            @Override
            public boolean onNextClicked() {
                tryPlayNextEpisode(downloadedVideoItems, startIndex);
                return true;
            }

            @Override
            public boolean onRewindClicked() {
                return false;
            }

            @Override
            public boolean onFastForwardClicked() {
                return false;
            }

        });
    }

    private void tryShowPreviousButton(List<DownloadedVideoItem> downloadedVideoItems,
                                       int startIndex, VideoControls videoControls) {
        int previousIndex = startIndex - 1;
        try {
            DownloadedVideoItem previousOnList = downloadedVideoItems.get(previousIndex);
            if (previousOnList != null) {
                videoControls.setPreviousButtonRemoved(false);
            } else {
                videoControls.setPreviousButtonRemoved(true);
            }
        } catch (Exception e) {
            videoControls.setPreviousButtonRemoved(true);
        }
    }

    private void tryShowNextButton(List<DownloadedVideoItem> downloadedVideoItems,
                                   int startIndex, VideoControls videoControls) {
        int nextIndex = startIndex + 1;
        try {
            DownloadedVideoItem nextOnList = downloadedVideoItems.get(nextIndex);
            if (nextOnList != null) {
                videoControls.setNextButtonRemoved(false);
            } else {
                videoControls.setNextButtonRemoved(true);
            }
        } catch (Exception e) {
            videoControls.setNextButtonRemoved(true);
        }
    }

    private void tryPlayPreviousEpisode(List<DownloadedVideoItem> downloadedVideoItems,
                                        int startIndex) {
        try {
            DownloadedVideoItem previous = downloadedVideoItems.get(startIndex - 1);
            if (previous != null) {
                playVideos(downloadedVideoItems, downloadedVideoItems.indexOf(previous));
            }
        } catch (Exception e) {
            UiUtils.showSafeToast("Error playing previous episode.Please try again later.");
        }
    }

    private void tryPlayNextEpisode(List<DownloadedVideoItem> downloadedVideoItems,
                                    int startIndex) {
        int nextIndex = startIndex + 1;
        try {
            DownloadedVideoItem next = downloadedVideoItems.get(nextIndex);
            if (next != null) {
                playVideos(downloadedVideoItems, downloadedVideoItems.indexOf(next));
            }
        } catch (Exception e) {
            if (nextIndex >= downloadedVideoItems.size()) {
                removePlayer();
            } else {
                UiUtils.showSafeToast("Error playing next episode.Please try again later.");
                MolvixLogger.d(MolvixVideoPlayerView.class.getSimpleName(), "VideoPlayBack error " + e.getMessage());
            }
        }
    }

    public void cleanUpVideoView() {
        try {
            if (videoView.isPlaying()) {
                videoView.stopPlayback();
            }
            videoView.release();
        } catch (Exception ignored) {

        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int newOrientation = newConfig.orientation;
        currentOrientation.set(newOrientation);
        if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterImmersiveMode();
        } else {
            leaveImmersiveMode();
        }
    }

    public void trySaveCurrentPlayerPosition() {
        File activeFile = activeFileReference.get();
        if (activeFile != null) {
            try {
                long currentPosition = videoView.getCurrentPosition();
                if (currentPosition != 0) {
                    AppPrefs.persistMediaPlayBackPositionFor(activeFile, currentPosition);
                }
            } catch (Exception ignored) {

            }
        }
    }

    public boolean isVideoPlaying() {
        return videoView.isPlaying();
    }

    public void pauseVideo() {
        videoView.pause();
    }

    public void tryResumeVideo() {
        if (!videoView.isPlaying()) {
            videoView.start();
        }
    }

}
