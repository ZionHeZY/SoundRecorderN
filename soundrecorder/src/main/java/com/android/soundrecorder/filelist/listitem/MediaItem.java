package com.android.soundrecorder.filelist.listitem;

import android.database.Cursor;
import android.provider.MediaStore;

public class MediaItem extends BaseListItem {
    private long mDateModified;
    private long mDuration;
    public enum PlayStatus {
        NONE, PLAYING, PAUSE
    }
    private PlayStatus mPlayStatus = PlayStatus.NONE;

    public interface ItemPlayStatusListener {
        void onPlayStatusChanged(PlayStatus status);
    }

    protected ItemPlayStatusListener mPlayStatusListener;

    public void setItemPlayStatusListener(ItemPlayStatusListener listener) {
        mPlayStatusListener = listener;
    }

    public MediaItem(Cursor cursor) {
        int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
        int dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
        int titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
        int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
        int modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
        mId = cursor.getLong(idIndex);
        mPath = cursor.getString(dataIndex);
        mTitle = cursor.getString(titleIndex);
        mDuration = cursor.getLong(durationIndex);
        mDateModified = cursor.getLong(modifiedIndex) * 1000; // second to millisecond
        setItemType(BaseListItem.TYPE_MEDIA_ITEM);
        setSelectable(true);
        setSupportedOperation(SUPPORT_ALL);
    }

    public long getDateModified() {
        return mDateModified;
    }

    public long getDuration() {
        return mDuration;
    }

    public PlayStatus getPlayStatus() {
        return mPlayStatus;
    }

    public void setPlayStatus(PlayStatus status) {
        if (mPlayStatus != status) {
            mPlayStatus = status;
            if (mPlayStatusListener != null) {
                mPlayStatusListener.onPlayStatusChanged(status);
            }
        }
    }

    @Override
    public void copyFrom(BaseListItem item) {
        super.copyFrom(item);
        if (item instanceof MediaItem) {
            mDateModified = ((MediaItem) item).getDateModified();
            mDuration = ((MediaItem) item).getDuration();
            setPlayStatus(((MediaItem) item).getPlayStatus());
        }
    }
}
