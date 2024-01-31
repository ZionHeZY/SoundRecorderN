package com.android.soundrecorder.filelist.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.android.soundrecorder.filelist.listitem.BaseListItem;
import com.android.soundrecorder.filelist.listitem.MediaItem;

import java.io.IOException;

public class Player implements MediaPlayer.OnCompletionListener,MediaPlayer.OnPreparedListener{
    private Context mApplicationContext;

    private PlayerPanel mPlayerPanel;
    private MediaItem mMediaItem;
    private MediaPlayer mPlayer = null;
    private final static String TAG = "Player";

    private static final int UPDATE_FREQ = 1000;
    private boolean isPrepared = false;

    private final Handler mHandler = new Handler();
    private Runnable mUpdateProgress = new Runnable() {
        public void run() {
            updateUi();
        }
    };

    private MediaPlayer.OnErrorListener mErrorListener = new MediaPlayer.OnErrorListener() {
        public boolean onError(MediaPlayer mp, int what, int extra) {
            stopPlayer();
            return true;
        }
    };

    @Override
    public void onCompletion(MediaPlayer mp) {
        stopPlayer();
    }

    public Player(Context context, PlayerPanel playerPanel) {
        mApplicationContext = context;
        mPlayerPanel = playerPanel;
        mPlayerPanel.setPlayerButtonClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPlayer == null) {
                    startPlayer();
                } else if (mPlayer.isPlaying()) {
                    pausePlayer();
                } else {
                    resumePlayer();
                }
            }
        });
    }

    public void setPlayerPanelLayoutListener(PlayerPanel.LayoutChangedListener listener) {
        if (mPlayerPanel != null) {
            mPlayerPanel.setLayoutChangedListener(listener);
        }
    }

    public void setItem(MediaItem item) {
        if (mMediaItem != null) {
            mMediaItem.setPlayStatus(MediaItem.PlayStatus.NONE);
        }
        mMediaItem = item;
        resetUi();
    }

    public MediaItem getMediaItem() {
        return mMediaItem;
    }

    public boolean isItemUsing(BaseListItem item) {
        if (item instanceof MediaItem) {
            return item.equals(mMediaItem);
        }
        return false;
    }

    private void updateUi() {
        if (mPlayerPanel != null && mPlayer != null) {
            mPlayerPanel.updateTitle(mMediaItem.getTitle());
            mPlayerPanel.updateProgress(mPlayer.getCurrentPosition(), mMediaItem.getDuration());
            boolean isPlaying = mPlayer.isPlaying();
            mPlayerPanel.updatePlayerStatus(isPlaying);
            if (isPlaying) {
                mHandler.postDelayed(mUpdateProgress, UPDATE_FREQ);
                mMediaItem.setPlayStatus(MediaItem.PlayStatus.PLAYING);
            } else {
                mMediaItem.setPlayStatus(MediaItem.PlayStatus.PAUSE);
            }
        }
    }

    private void resetUi() {
        mMediaItem.setPlayStatus(MediaItem.PlayStatus.PAUSE);
        if (mPlayerPanel != null) {
            mPlayerPanel.updateTitle(mMediaItem.getTitle());
            mPlayerPanel.updateProgress(0, mMediaItem.getDuration());
            mPlayerPanel.updatePlayerStatus(false);
        }
    }

    public void startPlayer() {
        stopPlayer();

        if (mPlayerPanel != null) {
            mPlayerPanel.setVisibility(View.VISIBLE);
        }

        mPlayer = new MediaPlayer();

        try {
            mPlayer.setDataSource(mMediaItem.getPath());
            mPlayer.setOnCompletionListener(this);
            mPlayer.setOnErrorListener(mErrorListener);

            mPlayer.setOnPreparedListener(this);

            isPrepared = false;
            mPlayer.prepareAsync();
        } catch (IOException e) {
            mPlayer = null;
            return;
        }

        updateUi();
    }

    public void pausePlayer() {
        if (mPlayer == null || isPrepared == false) return;
        mPlayer.pause();
        updateUi();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (mPlayer == null) return;

        //There may be other music stream are playing in background, in order to stop them,
        //we should get audio focus before start playing
        int audioFocusResult = requestAudioFocus();
        if (AudioManager.AUDIOFOCUS_REQUEST_GRANTED != audioFocusResult) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "requestAudioFocus failed!");
            }

            stopPlayer();
            return;
        }

        isPrepared = true;
        mPlayer.start();
        updateUi();
    }

    private void resumePlayer() {
        if (mPlayer == null || isPrepared == false) return;

        mPlayer.start();
        updateUi();
    }

    public void stopPlayer() {
        abandonAudioFocus();

        if (mPlayer == null) return;

        mPlayer.stop();
        mPlayer.release();
        mPlayer = null;
        resetUi();
    }

    public void destroyPlayer() {
        stopPlayer();
        hidePlayer();
    }

    protected void hidePlayer() {
        if (mMediaItem != null) {
            mMediaItem.setPlayStatus(MediaItem.PlayStatus.NONE);
        }
        if (mPlayerPanel != null) {
            mPlayerPanel.setVisibility(View.GONE);
        }
    }

    public boolean isPlayShown() {
        return (mPlayerPanel != null && mPlayerPanel.getVisibility() == View.VISIBLE);
    }

    public View getPlayerPanel() {
        return mPlayerPanel;
    }

    public void onItemDeleted() {
        stopPlayer();
        mMediaItem = null;
        hidePlayer();
    }

    public void onItemChanged(MediaItem newItem) {
        mMediaItem = newItem;
        updateUi();
    }

    private int requestAudioFocus() {
        AudioManager am = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        return am.requestAudioFocus(mAudioFocusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        am.abandonAudioFocus(mAudioFocusChangeListener);
    }

    private AudioManager.OnAudioFocusChangeListener mAudioFocusChangeListener
            = new AudioManager.OnAudioFocusChangeListener() {

        @Override
        public void onAudioFocusChange(int focusChange) {
            if (AudioManager.AUDIOFOCUS_LOSS == focusChange) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "audio focus loss, stop player");
                }
                stopPlayer();
            }
        }
    };
}
