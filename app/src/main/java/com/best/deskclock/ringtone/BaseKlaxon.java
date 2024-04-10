/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.best.deskclock.ringtone;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Objects;

import android.app.NotificationManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;

import com.best.deskclock.LogUtils;
import com.best.deskclock.R;
import com.best.deskclock.Utils;
import com.best.deskclock.provider.AlarmInstance;

/**
 * Manages playing ringtone and vibrating the device.
 */
public class BaseKlaxon {

    private static boolean sStarted = false;
    private static AudioManager sAudioManager = null;
    private static MediaPlayer sMediaPlayer = null;
    private static List<Uri> mSongs = new ArrayList<>();
    private static int sCurrentIndex;
    private static int sSavedVolume;
    private static int sMaxVolume;
    private static boolean sIncreasingVolume;
    private static boolean sRandomPlayback;
    private static long sCrescendoDuration;
    private static long mCrescendoStopTime;
    private static boolean sFirstFile;
    private static boolean sRandomMusicMode;
    private static boolean sLocalMediaMode;
    private static boolean sPlayFallbackAlarm;
    private static int mStream;
    private static int mRingtonePlayRetries = 0;

    // Internal messages
    private static final int INCREASING_VOLUME = 1001;

    private static final Handler sHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == INCREASING_VOLUME) {
                if (sStarted && adjustVolume()) {
                    sHandler.sendEmptyMessageDelayed(INCREASING_VOLUME, sCrescendoDuration);
                }
            }
        }
    };

    public static void stop(Context context) {
        if (sStarted) {
            LogUtils.v("AlarmKlaxon.stop()");

            sStarted = false;
            sHandler.removeMessages(INCREASING_VOLUME);
            // reset to default from before
            sAudioManager.setStreamVolume(mStream, sSavedVolume, 0);
            sAudioManager.abandonAudioFocus(null);

            // Stop audio playing
            if (sMediaPlayer != null) {
                sMediaPlayer.stop();
                sMediaPlayer.reset();
                sMediaPlayer.release();
                sMediaPlayer = null;
            }

            ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE)).cancel();
        }
    }

    public static void start(final Context context, Uri alarmNoise, long crescendoDuration, int stream, int volume, boolean shuffle) {

        // Make sure we are stop before starting
        stop(context);

        LogUtils.i("BaseKlaxon.start() ");

        sCrescendoDuration = crescendoDuration;
        LogUtils.v("Volume increase interval " + sCrescendoDuration);

        final Context appContext = context.getApplicationContext();
        sAudioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        // save current value
        mStream = stream;
        sSavedVolume = sAudioManager.getStreamVolume(mStream);
        sIncreasingVolume = crescendoDuration > 0;
        sRandomPlayback = shuffle;
        sFirstFile = true;
        sStarted = true;

        sMaxVolume = calcNormalizedVolume(volume);
        if (sMaxVolume == -1) {
            // calc from current alarm volume
            sMaxVolume = calcNormalizedVolume(calcNormalizedStreamVolume(mStream));
        }

        sRandomMusicMode = false;
        sLocalMediaMode = false;
        sPlayFallbackAlarm = false;

        sCurrentIndex = 0;
        if (alarmNoise != null) {
            LogUtils.i("isn't null", alarmNoise);
            if (MediaUtils.isRandomUri(alarmNoise.toString())) {
                sRandomMusicMode = true;
                sRandomPlayback = true;
                mSongs = MediaUtils.getRandomMusicFiles(context, 50);
                if (mSongs.size() != 0) {
                    alarmNoise = mSongs.get(0);
                } else {
                    LogUtils.e("fallback(false category as random)");
                    alarmNoise = null;
                    sRandomMusicMode = false;
                }
            } else if (MediaUtils.isLocalPlaylistType(alarmNoise.toString())) {
                // can fail if no external storage permissions
                try {
                    sLocalMediaMode = true;
                    mSongs.clear();
                    if (MediaUtils.isLocalAlbumUri(alarmNoise.toString())) {
                        collectAlbumSongs(context, alarmNoise);
                    }
                    if (MediaUtils.isLocalArtistUri(alarmNoise.toString())) {
                        collectArtistSongs(context, alarmNoise);
                    }
                    if (MediaUtils.isStorageUri(alarmNoise.toString())) {
                        collectFiles(alarmNoise);
                    }
                    if (MediaUtils.isLocalPlaylistUri(alarmNoise.toString())) {
                        collectPlaylistSongs(context, alarmNoise);
                    }
                    if (mSongs.size() != 0) {
                        alarmNoise = mSongs.get(0);
                    } else {
                        LogUtils.e("fallback(false category as playlist type)");
                        alarmNoise = null;
                        sLocalMediaMode = false;
                    }
                } catch (Exception ex) {
                    LogUtils.e("Error accessing media contents", ex);
                    // fallback
                    alarmNoise = null;
                    sLocalMediaMode = false;
                }
            }
        }
        if (alarmNoise == null) {
            LogUtils.e("Play default alarm");
            sPlayFallbackAlarm = true;
        } else if (AlarmInstance.NO_RINGTONE_URI.equals(alarmNoise)) {
            // silent
            alarmNoise = null;
        }
        // do not play alarms if alarm volume is 0
        // this can only happen if "use system alarm volume" is used
        boolean playSound = (alarmNoise != null || sPlayFallbackAlarm) && sMaxVolume != 0;
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        int filter = notificationManager.getCurrentInterruptionFilter();
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            playSound = false;
        }

        if (playSound) {
            playAlarm(context, alarmNoise);
        }
    }

    private static void playAlarm(final Context context, final Uri alarmNoise) {
        LogUtils.i("playAlarm");
        if (sMediaPlayer != null) {
            sMediaPlayer.reset();
        }
        if (!sStarted) {
            return;
        }
        sMediaPlayer = new MediaPlayer();
        sMediaPlayer.setOnErrorListener((mp, what, extra) -> {
            LogUtils.e("Error playing " + alarmNoise);
            if (sLocalMediaMode || sRandomMusicMode) {
                LogUtils.e("Skipping file");
                mSongs.remove(alarmNoise);
                nextSong(context);
            } else {
                LogUtils.e("playFallbackAlarm 1");
                playFallbackAlarm(context);
            }
            return true;
        });

        if (sLocalMediaMode || sRandomMusicMode) {
            sMediaPlayer.setOnCompletionListener(mp -> nextSong(context));
        }

        try {
            if (sPlayFallbackAlarm || alarmNoise == null) {
                LogUtils.e("Using the fallback ringtone 1");
                setDataSourceFromResource(context, sMediaPlayer, R.raw.alarm_expire);
            } else {
                LogUtils.v("startAlarm for :" + alarmNoise);
                sMediaPlayer.setDataSource(context, alarmNoise);
            }
            startAlarm(sMediaPlayer);
        } catch (Exception ex) {
            LogUtils.e("Error playing " + alarmNoise, ex);
            if (sLocalMediaMode || sRandomMusicMode) {
                LogUtils.e("Skipping file");
                mSongs.remove(alarmNoise);
                nextSong(context);
            } else {
                LogUtils.e("Using the fallback ringtone 2");
                // The alarmNoise may be on the sd card which could be busy right
                // now. Use the fallback ringtone.
                try {
                    // Must reset the media player to clear the error state.
                    sMediaPlayer.reset();
                    setDataSourceFromResource(context, sMediaPlayer, R.raw.alarm_expire);
                    startAlarm(sMediaPlayer);
                } catch (Exception ex2) {
                    // At this point we just don't play anything.
                    LogUtils.e("Failed to play fallback ringtone", ex2);
                }
            }
        }
    }

    // Do the common stuff when starting the alarm.
    private static void startAlarm(MediaPlayer player) {
        if (!sStarted) {
            return;
        }
        LogUtils.v("startAlarm");

        LogUtils.v("Using audio stream " + (mStream == AudioManager.STREAM_MUSIC ? "Music" : "Alarm"));
        player.setAudioStreamType(mStream);
        sAudioManager.requestAudioFocus(null, mStream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

        if (!sRandomMusicMode && !sLocalMediaMode) {
            player.setLooping(true);
        }

        player.setOnPreparedListener(player1 -> {
            LogUtils.v("onPrepared");

            // only start volume handling on the first invocation
            if (sFirstFile) {
                if (sIncreasingVolume) {
                    startVolumeIncrease();
                } else {
                    sAudioManager.setStreamVolume(mStream, sMaxVolume, 0);
                    LogUtils.v("Alarm volume " + sMaxVolume);
                }
                sFirstFile = false;
            }
            player1.start();
        });
        player.prepareAsync();
    }

    private static void setDataSourceFromResource(Context context, MediaPlayer player, int res) throws IOException {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
        }
    }

    /**
     * we use the current stream volume to play on the music stream
     * if the stream is alarm we must scale the alarm volume inside
     * the music volume range to be properly processed by
     */
    public static int calcNormalizedStreamVolume(int audioStream) {
        int alarmVolume = sAudioManager.getStreamVolume(audioStream);
        if (alarmVolume == 0) {
            return 0;
        }
        if (audioStream == AudioManager.STREAM_ALARM) {
            int maxMusicVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int maxAlarmVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            return (int) (((float) alarmVolume / (float) maxAlarmVolume) * (float) maxMusicVolume);
        }
        return alarmVolume;
    }

    /**
     * volume is stored based on music volume steps
     * so if we use the alarm stream to play we must scale the
     * volume inside the alarm volume range
     */
    private static int calcNormalizedVolume(int alarmVolume) {
        if (alarmVolume == -1) {
            // use system stream volume
            return alarmVolume;
        }
        if (mStream == AudioManager.STREAM_ALARM) {
            int maxMusicVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int maxAlarmVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            return (int) (((float) alarmVolume / (float) maxMusicVolume) * (float) maxAlarmVolume) + 1;
        }
        return alarmVolume;
    }

    private static void nextSong(final Context context) {
        if (mSongs.size() == 0) {
            LogUtils.v("playFallbackAlarm 2");
            playFallbackAlarm(context);
            return;
        }
        sCurrentIndex++;
        // restart if on end
        if (sCurrentIndex >= mSongs.size()) {
            sCurrentIndex = 0;
            if (sRandomPlayback) {
                Collections.shuffle(mSongs);
            }
        }
        Uri song = mSongs.get(sCurrentIndex);
        playAlarm(context, song);
    }

    private static void collectFiles(Uri folderUri) {
        mSongs.clear();

        File folder = new File(folderUri.getPath());
        if (folder.exists() && folder.isDirectory()) {
            for (final File fileEntry : Objects.requireNonNull(folder.listFiles())) {
                if (!fileEntry.isDirectory()) {
                    if (MediaUtils.isValidAudioFile(fileEntry.getName())) {
                        mSongs.add(Uri.fromFile(fileEntry));
                    }
                } else {
                    collectSub(fileEntry);
                }
            }
            if (sRandomPlayback) {
                Collections.shuffle(mSongs);
            } else {
                Collections.sort(mSongs);
            }
        }
    }

    private static void collectSub(File folder) {
        if (folder.exists() && folder.isDirectory()) {
            for (final File fileEntry : Objects.requireNonNull(folder.listFiles())) {
                if (!fileEntry.isDirectory()) {
                    if (MediaUtils.isValidAudioFile(fileEntry.getName())) {
                        mSongs.add(Uri.fromFile(fileEntry));
                    }
                } else {
                    collectSub(fileEntry);
                }
            }
        }
    }

    private static void collectAlbumSongs(Context context, Uri albumUri) {
        mSongs.clear();
        mSongs = MediaUtils.getAlbumSongs(context, albumUri);
        if (sRandomPlayback) {
            Collections.shuffle(mSongs);
        }
    }

    private static void collectArtistSongs(Context context, Uri artistUri) {
        mSongs.clear();
        mSongs = MediaUtils.getArtistSongs(context, artistUri);
        if (sRandomPlayback) {
            Collections.shuffle(mSongs);
        }
    }

    private static void collectPlaylistSongs(Context context, Uri playlistUri) {
        mSongs.clear();
        mSongs = MediaUtils.getPlaylistSongs(context, playlistUri);
        if (sRandomPlayback) {
            Collections.shuffle(mSongs);
        }
    }

    private static void playFallbackAlarm(final Context context) {
        if (!sPlayFallbackAlarm) {
            sRandomMusicMode = false;
            sLocalMediaMode = false;
            sPlayFallbackAlarm = true;
            playAlarm(context, null);
        }
    }

    private static void startVolumeIncrease() {
        if (adjustVolume()) {
            sHandler.sendEmptyMessageDelayed(INCREASING_VOLUME, 50);
        }
    }
    public static boolean adjustVolume() {

        // If ringtone is absent, ignore volume adjustment.
        if (sMediaPlayer == null) {
            sCrescendoDuration = 0;
            mCrescendoStopTime = 0;
            return false;
        }

        // If ringtone is not playing, to avoid being muted forever recheck
        // to ensure reliability.
        if (!sStarted && !sMediaPlayer.isPlaying()) {
            if (mRingtonePlayRetries < 10) {
                mRingtonePlayRetries++;
                // Reuse the crescendo messaging looper to return here
                // again.
                return true;
            }

            mRingtonePlayRetries = 0;
            sCrescendoDuration = 0;
            mCrescendoStopTime = 0;
            return false;
        }

        if (mRingtonePlayRetries > 0) {
            mRingtonePlayRetries = 0;
            // Compute again the time at which the crescendo will stop.
            mCrescendoStopTime = Utils.now() + sCrescendoDuration;
        }

        // If the crescendo is complete set the volume to the maximum; we're done.
        final long currentTime = Utils.now();
        if (currentTime > mCrescendoStopTime) {
            sCrescendoDuration = 0;
            mCrescendoStopTime = 0;
            sMediaPlayer.setVolume(1, 1);
            return false;
        }

        final float volume = computeVolume(currentTime, mCrescendoStopTime, sCrescendoDuration);
        sMediaPlayer.setVolume(volume,volume);

        // Schedule the next volume bump in the crescendo.
        return true;
    }



    /**
     * @param currentTime current time of the device
     * @param stopTime    time at which the crescendo finishes
     * @param duration    length of time over which the crescendo occurs
     * @return the scalar volume value that produces a linear increase in volume (in decibels)
     */
    private static float computeVolume(long currentTime, long stopTime, long duration) {
        // Compute the percentage of the crescendo that has completed.
        final float elapsedCrescendoTime = stopTime - currentTime;
        final float fractionComplete = 1 - (elapsedCrescendoTime / duration);

        // Use the fraction to compute a target decibel between -40dB (near silent) and 0dB (max).
        final float gain = (fractionComplete * 40) - 40;

        // Convert the target gain (in decibels) into the corresponding volume scalar.
        final float volume = (float) Math.pow(10f, gain / 20f);

        LogUtils.v("Ringtone crescendo %,.2f%% complete (scalar: %f, volume: %f dB)",
                fractionComplete * 100, volume, gain);

        return volume;
    }
}