package com.android.soundrecorder.filelist.viewholder;

import android.view.View;
import android.widget.TextView;

import com.android.soundrecorder.R;
import com.android.soundrecorder.filelist.listitem.BaseListItem;
import com.android.soundrecorder.filelist.listitem.MediaItem;
import com.android.soundrecorder.filelist.ui.WaveIndicator;

import java.text.SimpleDateFormat;
import java.util.Date;

import util.Utils;


public class MediaItemViewHolder extends BaseViewHolder {
    private TextView mDateModifiedView;
    private TextView mDurationView;
    private WaveIndicator mWaveIndicator;
    private SimpleDateFormat mDateFormatter;

    public MediaItemViewHolder(View itemView, int rootLayoutId, boolean isHH) {
        super(itemView, rootLayoutId);
        mDateModifiedView = (TextView) itemView.findViewById(R.id.list_item_date_modified);
        mDurationView = (TextView) itemView.findViewById(R.id.list_item_duration);
        mWaveIndicator = (WaveIndicator) itemView.findViewById(R.id.list_wave_indicator);
        //modify by hezhongyang @20161129 for bug132155 start
        if (isHH) {
            mDateFormatter = new SimpleDateFormat(itemView.getResources().getString(
                    R.string.list_item_date_modified_format), java.util.Locale.US);
        } else {
            mDateFormatter = new SimpleDateFormat(itemView.getResources().getString(
                    R.string.list_item_date_modified_other_format), java.util.Locale.US);
        }
        //modify by hezhongyang @20161129 for bug132155 start
    }

    @Override
    public void setItem(BaseListItem item) {
        super.setItem(item);
        if (item instanceof MediaItem) {
            long dateModified = ((MediaItem) item).getDateModified();
            Date date = new Date(dateModified);
            mDateModifiedView.setText(mDateFormatter.format(date));

            long duration = ((MediaItem) item).getDuration() / 1000; // millisecond to second
            mDurationView.setText(Utils.timeToString(mDurationView.getContext(), duration));

            updateWaveIndicator(((MediaItem) item).getPlayStatus());
        }
    }

    private void updateWaveIndicator(MediaItem.PlayStatus status) {
        if (status == MediaItem.PlayStatus.PLAYING) {
            mWaveIndicator.setVisibility(View.VISIBLE);
            mWaveIndicator.animate(true);
        } else if (status == MediaItem.PlayStatus.PAUSE) {
            mWaveIndicator.setVisibility(View.VISIBLE);
            mWaveIndicator.animate(false);
        } else {
            mWaveIndicator.setVisibility(View.GONE);
            mWaveIndicator.animate(false);
        }
    }
}
