package com.molvix.android.utils;

import com.molvix.android.database.MolvixDB;
import com.molvix.android.managers.FileDownloadManager;
import com.molvix.android.models.Episode;
import com.molvix.android.preferences.AppPrefs;

import java.util.Set;

public class DownloaderUtils {
    public static void checkAndResumePausedDownloads() {
        if (ConnectivityUtils.isConnected()) {
            Set<String> pausedDownloads = AppPrefs.getInProgressDownloads();
            if (!pausedDownloads.isEmpty()) {
                for (String episodeId : pausedDownloads) {
                    Episode episode = MolvixDB.getEpisode(episodeId);
                    if (episode != null && AppPrefs.getEpisodeDownloadProgress(episodeId) == -1) {
                        FileDownloadManager.downloadEpisode(episode);
                    }
                }
            }
        } else {
            UiUtils.showSafeToast("Failed to resume downloads due to a network error!");
        }
    }
}
